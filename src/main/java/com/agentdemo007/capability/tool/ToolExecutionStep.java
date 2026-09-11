package com.agentdemo007.capability.tool;

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

/**
 * 工具执行步骤（第四层·{@code @Order(650)}，紧随 {@code RouteDispatchStep}、先于 {@code ContextBuilder}）。
 *
 * <p>对标准化 Query 经 {@link ToolCallExecutor}（② Slice 3·option A 数据步·替手撸 {@code ToolExecutor}）
 * 单次前向 LLM function-calling：模型出 {@code tool_calls} 则经 {@link ResilientToolExecutor} 执行真 {@code @Tool}
 * → 结果列表写入 {@code context.toolResults}（客观数据层消费，下游 ContextBuilder+终答步不动，收口最稳）；
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
        String query = resolveQuery(context);
        try {
            List<String> results = executor.execute(query);
            if (!results.isEmpty()) {
                context.setToolResults(results);
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

    private String resolveQuery(PipelineContext context) {
        StandardQuery sq = context.standardQuery();
        return (sq != null) ? sq.text() : context.rawInput();
    }
}
