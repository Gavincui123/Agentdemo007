package com.agentdemo007.langgraph;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.common.pipeline.StepOutcomeAuditor;
import com.agentdemo007.observability.AgentMetrics;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 图执行器（Phase 14·终端收口）。
 *
 * <p>把同一份 {@link PipelineStep}（经 {@link GraphNode} 适配、由 {@link AgentStateGraph} 编译）
 * 驱动为 langgraph4j 图执行，终态收口为 {@link PipelineResult}——镜像线性 {@code PipelineOrchestrator}
 * 的 {@code run()}/{@code terminal()} 语义，使图编排与线性编排产出一致（验收：编排模式与直接链路输出结果一致）。
 *
 * <p>终端路径（读终态 {@link GraphNode#OUTCOME_KEY}）：
 * <ul>
 *   <li>末次产出为 {@link StepOutcome.ShortCircuit} → 话术短路（零 LLM，①话术短路）；
 *       步骤异常已由 {@link GraphNode} 捕获为 {@code ShortCircuit(INTERNAL)}（per-step 收口）。</li>
 *   <li>否则 → {@link #terminal}：degraded→话术/回复，否则 ok（镜像线性 terminal）。</li>
 * </ul>
 * 图基础设施异常（构建/驱动）在此收口到 INTERNAL 话术 + 审计（全局兜底，不抛 5xx，§5.12）。
 *
 * <p>每请求新建 {@link AgentStateGraph}（{@code NoCloneStateSerializer} 非线程安全，per-request）。
 */
public class GraphExecutor implements PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(GraphExecutor.class);

    /** 默认最大迭代护栏（§5.13 死循环护栏兜底；可由 3-参构造器或配置覆盖）。 */
    static final int DEFAULT_MAX_ITERATIONS = 25;

    private final List<PipelineStep> steps;
    private final DegradationPhraseCenter phraseCenter;
    private final int maxIterations;
    private final AgentMetrics metrics;

    public GraphExecutor(List<PipelineStep> steps, DegradationPhraseCenter phraseCenter) {
        this(steps, phraseCenter, DEFAULT_MAX_ITERATIONS, AgentMetrics.NO_OP);
    }

    public GraphExecutor(List<PipelineStep> steps, DegradationPhraseCenter phraseCenter, int maxIterations) {
        this(steps, phraseCenter, maxIterations, AgentMetrics.NO_OP);
    }

    /** 全参构造器：{@code LangGraphConfig} @Bean 注入真实 {@link AgentMetrics}（终端指标收口）。 */
    public GraphExecutor(List<PipelineStep> steps, DegradationPhraseCenter phraseCenter,
                         int maxIterations, AgentMetrics metrics) {
        this.steps = steps;
        this.phraseCenter = phraseCenter;
        this.maxIterations = maxIterations;
        this.metrics = metrics;
    }

    @Override
    public PipelineResult run(PipelineContext context) {
        long start = System.nanoTime();
        try {
            return runGraph(context);
        } finally {
            metrics.recordPipelineDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private PipelineResult runGraph(PipelineContext context) {
        List<GraphNode> nodes = steps.stream().map(GraphNode::new).toList();
        CompiledGraph<AgentState> compiled;
        try {
            compiled = new AgentStateGraph(nodes).build().compile();
            compiled.setMaxIterations(maxIterations); // 死循环护栏（§5.13）
        } catch (GraphStateException e) {
            log.error("图定义构建失败，收口到 INTERNAL 话术：{}", e.getMessage(), e);
            StepOutcomeAuditor.auditException(context, "graph", e.getMessage());
            metrics.recordOutcome(AgentMetrics.Outcome.SHORT_CIRCUIT, DegradationScenario.INTERNAL);
            return PipelineResult.shortCircuit(phraseCenter.phrase(DegradationScenario.INTERNAL),
                    DegradationScenario.INTERNAL);
        }

        Optional<AgentState> resultOpt;
        try {
            resultOpt = compiled.invoke(Map.of(GraphNode.CONTEXT_KEY, context));
        } catch (Exception e) {
            log.error("图执行异常，收口到 INTERNAL 话术：{}", e.getMessage(), e);
            StepOutcomeAuditor.auditException(context, "graph", e.getMessage());
            metrics.recordOutcome(AgentMetrics.Outcome.SHORT_CIRCUIT, DegradationScenario.INTERNAL);
            return PipelineResult.shortCircuit(phraseCenter.phrase(DegradationScenario.INTERNAL),
                    DegradationScenario.INTERNAL);
        }

        AgentState finalState = resultOpt.orElseThrow(() -> new IllegalStateException(
                "langgraph4j 图执行返回空状态（traceId=" + context.traceId() + "）"));
        StepOutcome finalOutcome = finalState.<StepOutcome>value(GraphNode.OUTCOME_KEY).orElse(null);
        if (finalOutcome instanceof StepOutcome.ShortCircuit sc) {
            // 短路终态（含异常收口的 INTERNAL）：取话术，零 LLM（①话术短路）
            metrics.recordOutcome(AgentMetrics.Outcome.SHORT_CIRCUIT, sc.scenario());
            return PipelineResult.shortCircuit(phraseCenter.phrase(sc.scenario()), sc.scenario());
        }
        // 正常完成或降级完成 → terminal 收口（镜像线性编排器）
        return terminal(context);
    }

    /**
     * 终态收口：镜像线性 {@code PipelineOrchestrator} 的 {@code terminal()} 逐字一致——
     * degraded→取话术/回复（reply 非空优先），否则 ok。同步记录终端 outcome 指标（④统一收口）。
     */
    private PipelineResult terminal(PipelineContext context) {
        String reply = context.finalReply();
        if (context.degraded()) {
            DegradationScenario s = (context.scenario() != null) ? context.scenario() : DegradationScenario.INTERNAL;
            String resolved = (reply != null && !reply.isBlank()) ? reply : phraseCenter.phrase(s);
            metrics.recordOutcome(AgentMetrics.Outcome.DEGRADED, s);
            return PipelineResult.degraded(resolved, s);
        }
        metrics.recordOutcome(AgentMetrics.Outcome.OK, null);
        return PipelineResult.ok(reply);
    }
}
