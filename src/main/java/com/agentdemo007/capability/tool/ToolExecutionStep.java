package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.PolicyFragment;
import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.session.model.StandardQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 工具执行步骤（第四层·{@code @Order(650)}，紧随 {@code RouteDispatchStep}、先于 {@code ContextBuilder}）。
 *
 * <p>对标准化 Query 经 {@link ToolCallExecutor}（② Slice 3·option A 数据步·替手撸 {@code ToolExecutor}）
 * 单次前向 LLM function-calling：模型出 {@code tool_calls} 则经 {@link ResilientToolExecutor} 执行真 {@code @Tool}
 * → 结果按 {@link ToolCallResult#category()} 路由 3 通道（RUNTIME→{@code runtimeFacts} / RAG→{@code ragFragments}
 * +{@code ragCitations} / COMPUTE→{@code toolResults}，[[business-tools-workflow-dag]] §2.2·用户钦定）；
 * 未出 {@code tool_calls} → 空过。落地收口（{@link StepOutcome}）：
 * <ul>
 *   <li>无工具调用 / 执行成功 → Proceed；</li>
 *   <li>工具熔断中（Phase 17 {@link ToolCircuitOpenException}）→ {@code ShortCircuit(TOOL_FAILURE)} 话术短路
 *       （零 LLM 兜底，不进工具探测，等冷却半开探针恢复）。</li>
 * </ul>
 *
 * <p>退役映射：旧 {@code ToolRecoverableException}（自纠正耗尽）catch 已删——LC4j {@link dev.langchain4j.service.tool.DefaultToolExecutor}
 * 原生吞 @Tool 异常→异常消息当工具结果返回（不抛），故 {@code ToolRecoverableException} 不透传至本步
 * （[[langchain4j-boot4-compat-findings]]：自纠正开箱即用）。输入取 {@code standardQuery}（缺失回退 {@code rawInput}）。
 */
@Component
@Order(650)
public class ToolExecutionStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionStep.class);

    private final ToolCallExecutor executor;
    private final AgentMetrics metrics;

    public ToolExecutionStep(ToolCallExecutor executor) {
        this(executor, AgentMetrics.NO_OP);
    }

    @Autowired
    public ToolExecutionStep(ToolCallExecutor executor, AgentMetrics metrics) {
        this.executor = executor;
        this.metrics = metrics;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        // #135 渐进消费·source 门控（[[routeplan-design]]）：routePlan 为真实 LLM 候选（source=
        // LLM_WITH_POLICY_CONSTRAINTS）且 needsBusinessTools=false → 跳过工具执行（省一次 function-calling
        // 调用）；DETERMINISTIC_FALLBACK/null 回退现有逻辑（跑 executor）——noop 测试/兜底候选不采信 routePlan，
        // 现有行为不破。requiredTools per-route 子集（ToolProvider）= Slice 4 留后；本步仅 needsBusinessTools 门控。
        RoutePlan rp = context.routePlan();
        if (rp != null && rp.source() == RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS
                && !rp.needsBusinessTools()) {
            return new StepOutcome.Proceed();
        }
        String query = resolveQuery(context);
        try {
            List<ToolCallResult> results = executor.execute(query);
            if (!results.isEmpty()) {
                routeResults(context, results);
                log.debug("工具执行完成：sessionId={} results={}", context.sessionId(), results);
            }
            metrics.recordTool(true);
            return new StepOutcome.Proceed();
        } catch (ToolCircuitOpenException e) {
            log.warn("工具熔断中，触发 TOOL_FAILURE 话术短路（零 LLM，不进工具探测）：sessionId={} tool={}",
                    context.sessionId(), e.toolName()); // 审计
            // 指标记录不得反噬降级：Micrometer 对非法 tag/重复不一致标签会抛，须吞掉保证 ShortCircuit 返回
            try {
                metrics.recordTool(false);
                metrics.recordToolCircuitOpen(e.toolName());
            } catch (Exception metricEx) {
                log.warn("指标记录失败，忽略（不影响降级短路）：{}", metricEx.getMessage());
            }
            return new StepOutcome.ShortCircuit(DegradationScenario.TOOL_FAILURE);
        }
    }

    /**
     * 按 category 路由工具结果到 3 通道（[[business-tools-workflow-dag]] §2.2·用户钦定）：
     * <ul>
     *   <li>RUNTIME → {@code context.runtimeFacts}（外部系统高置信事实，SystemAnchorLayer 消费进 Runtime 块）；</li>
     *   <li>RAG → 拆 JSON {@code {text,source}} → {@code ragFragments}+{@code ragCitations}（带 citation），
     *       畸形 JSON 兜底 raw 入 ragFragments、citation 空（②每步降级，不阻塞）；</li>
     *   <li>COMPUTE → {@code context.toolResults}（简单计算，现状不变）。</li>
     * </ul>
     */
    private void routeResults(PipelineContext context, List<ToolCallResult> results) {
        for (ToolCallResult r : results) {
            switch (r.category()) {
                case RUNTIME -> context.runtimeFacts().add(r.content());
                case RAG -> {
                    Optional<PolicyFragment> parsed = PolicyFragment.fromJson(r.content());
                    if (parsed.isPresent()) {
                        context.ragFragments().add(parsed.get().text());
                        context.ragCitations().add(parsed.get().source());
                    } else {
                        context.ragFragments().add(r.content()); // 兜底 raw（citation 空）
                    }
                }
                case COMPUTE -> context.toolResults().add(r.content());
            }
        }
    }

    private String resolveQuery(PipelineContext context) {
        StandardQuery sq = context.standardQuery();
        return (sq != null) ? sq.text() : context.rawInput();
    }
}
