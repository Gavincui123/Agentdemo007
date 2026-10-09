package com.agentdemo007.langgraph;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.common.pipeline.StepOutcomeAuditor;
import com.agentdemo007.common.progress.ProgressEmitter;
import com.agentdemo007.common.progress.ProgressEvent;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.observability.AgentTracer;
import io.opentelemetry.api.trace.SpanKind;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 图节点适配层（Phase 14·LangGraph 节点）。
 *
 * <p>把既有 {@link PipelineStep}（意图识别/能力执行/上下文构建/网关调用/输出校验）
 * 适配为 langgraph4j 节点——换引擎不换收口（{@code PipelineStep} 契约即节点适配层）。
 * 同一份 step 实现在线性编排（{@code PipelineOrchestrator}）与图编排下复用，
 * 保证两模式输出一致（验收：编排模式与直接链路输出结果一致）。
 *
 * <p><b>入参</b>：从 langgraph4j 共享状态取 {@link PipelineContext}（强类型收口状态对象，
 * 业务字段全部经其强类型访问器读写，不散落为 Map 键——§5.14；langgraph4j 的 Map 仅作引擎载体，
 * 真正业务状态永远在 PipelineContext 强类型字段内）。
 *
 * <p><b>出参</b>：调 {@code step.process(context)}，把产出的 {@link StepOutcome}（收口 sealed 类型）
 * 作为路由信号发射到状态更新图（{@link #OUTCOME_KEY}），供 {@code GraphEdge} 据此路。
 *
 * <p><b>职责边界</b>：本类做"跑步骤 + per-step 副作用 + 发射 outcome"——审计与 markDegraded
 * 经共享 {@link StepOutcomeAuditor} 收口（与线性 {@code PipelineOrchestrator} 同一份逻辑，④统一收口），
 * 使图编排下 {@code context.degraded()} 跨节点累积、{@code terminal()} 产出与线性一致；
 * 降级话术与终态 {@code PipelineResult} 组装由 {@code GraphExecutor} 终端收口。步骤抛异常 →
 * 审计 + 发射 {@code ShortCircuit(INTERNAL)} 供 {@code GraphEdge} 路由至 END（镜像线性 per-step catch）。
 */
public class GraphNode {

    /** langgraph4j 共享状态中 PipelineContext 的键（收口状态载体，非业务散字段）。 */
    public static final String CONTEXT_KEY = "__pipelineContext__";

    /** 状态更新图中 StepOutcome 路由信号的键（收口 sealed 类型，GraphEdge 据此路）。 */
    public static final String OUTCOME_KEY = "__stepOutcome__";

    private final PipelineStep step;
    private final AgentTracer tracer;
    private final AgentMetrics metrics;

    /** 既有构造（测试便利）：链路/指标均为空实现。 */
    public GraphNode(PipelineStep step) {
        this(step, AgentTracer.NO_OP, AgentMetrics.NO_OP);
    }

    /** 全参构造（Phase 23）：由 {@code GraphExecutor} 注入门面，per-node span 与耗时 Timer 镜像线性编排器。 */
    public GraphNode(PipelineStep step, AgentTracer tracer, AgentMetrics metrics) {
        this.step = step;
        this.tracer = tracer;
        this.metrics = metrics;
    }

    /** 节点名，委托包裹步骤（审计/日志用，镜像 {@link PipelineStep#name()}）。 */
    public String name() {
        return step.name();
    }

    /**
     * 适配为 langgraph4j 异步节点动作：取 context → 跑 process → 发射 outcome 信号。
     *
     * <p>{@code AsyncNodeAction.node_async(...)} 把同步 {@code NodeAction} 包成异步
     * （langgraph4j {@code addNode} 接受 AsyncNodeAction）。step.process 的受检异常
     * 由 NodeAction.apply 的 {@code throws Exception} 声明透传，图执行时收口到异常分支。
     */
    public AsyncNodeAction<AgentState> asAsyncNodeAction() {
        return AsyncNodeAction.node_async(state -> {
            PipelineContext context = state.<PipelineContext>value(CONTEXT_KEY)
                    .orElseThrow(() -> new IllegalStateException(
                            "langgraph4j 状态缺少 PipelineContext（键=" + CONTEXT_KEY
                                    + "）；图执行前须由 AgentStateGraph 注入"));
            ProgressEmitter emitter = context.emitter(); // #136 图模式进度对齐线性：NO_OP 默认，chatStream 注入 Sse 桥接
            emitter.emit(new ProgressEvent.StepStarted(step.name()));
            // Phase 23 per-step span：与线性编排器同点位同属性（瀑布图两模式一致）
            AgentTracer.SpanHandle stepSpan = tracer.startSpan("agent.step", SpanKind.INTERNAL,
                    "agent.step.name", step.name());
            long stepStart = System.nanoTime();
            StepOutcome outcome;
            try {
                outcome = step.process(context);
            } catch (Exception e) {
                // 异常收口（镜像线性编排器 per-step catch）：审计 + 发 INTERNAL 短路供路由至 END
                finishStepSpan(step.name(), stepSpan, "exception", DegradationScenario.INTERNAL, stepStart, e);
                StepOutcomeAuditor.auditException(context, step.name(), e.getMessage());
                emitter.emit(new ProgressEvent.StepFinished(step.name(),
                        ProgressEvent.Outcome.EXCEPTION, DegradationScenario.INTERNAL));
                return Map.of(OUTCOME_KEY,
                        new StepOutcome.ShortCircuit(DegradationScenario.INTERNAL));
            }
            // per-step 副作用（审计 + markDegraded）——经共享 StepOutcomeAuditor 收口，
            // 与线性编排器等价（degraded 须跨节点累积，否则 terminal 产出不一致）
            StepOutcomeAuditor.audit(context, step.name(), outcome);
            String outcomeTag = "proceed";
            DegradationScenario scenario = null;
            if (outcome instanceof StepOutcome.ShortCircuit sc) {
                outcomeTag = "short_circuit";
                scenario = sc.scenario();
                emitter.emit(new ProgressEvent.StepFinished(step.name(),
                        ProgressEvent.Outcome.SHORT_CIRCUIT, sc.scenario()));
            } else if (outcome instanceof StepOutcome.Degrade d) {
                outcomeTag = "degrade";
                scenario = d.scenario();
                emitter.emit(new ProgressEvent.StepFinished(step.name(),
                        ProgressEvent.Outcome.DEGRADE, d.scenario()));
            } else {
                // Proceed / Retry：推进下一节点（图模式下由 GraphEdge 路由，进度语义同线性）
                emitter.emit(new ProgressEvent.StepFinished(step.name(),
                        ProgressEvent.Outcome.PROCEED, null));
            }
            finishStepSpan(step.name(), stepSpan, outcomeTag, scenario, stepStart, null);
            return Map.of(OUTCOME_KEY, outcome);
        });
    }

    /** per-step span 收口（镜像线性编排器同名助手）：outcome/scenario 属性 + 耗时 Timer + end。 */
    private void finishStepSpan(String stepName, AgentTracer.SpanHandle stepSpan, String outcome,
                                DegradationScenario scenario, long stepStartNanos, Throwable error) {
        stepSpan.attr("agent.step.outcome", outcome);
        stepSpan.attr("agent.step.scenario", scenario != null ? scenario.name() : "none");
        if (error != null) {
            stepSpan.error(error);
        }
        metrics.recordStepDuration(stepName,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stepStartNanos), outcome);
        stepSpan.end();
    }
}
