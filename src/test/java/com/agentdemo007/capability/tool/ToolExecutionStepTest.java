package com.agentdemo007.capability.tool;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.session.model.StandardQuery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具执行步骤测试（第四层·ToolExecutionStep {@code @Order(650)}·② Slice 3 迁 {@link ToolCallExecutor}）。
 *
 * <p>落地收口契约：经 {@link PipelineContext} 读写、返回 {@link StepOutcome}：
 * <ul>
 *   <li>模型出 {@code tool_calls} 且执行成功 → 写入 {@code toolResults} + Proceed；</li>
 *   <li>模型未出 {@code tool_calls} → {@code toolResults} 空 + Proceed（正常对话）；</li>
 *   <li>工具熔断中（{@link ToolCircuitOpenException}）→ {@code ShortCircuit(TOOL_FAILURE)}
 *       话术短路（零 LLM 兜底，deg-009）。熔断路径详见 {@link ToolExecutionStepCircuitTest}。</li>
 * </ul>
 * 输入取 {@code standardQuery}（缺失回退 {@code rawInput}，与意图步骤一致）。
 *
 * <p>退役映射：旧 {@code ToolRecoverableException}（自纠正耗尽）catch 已删——LC4j
 * {@link dev.langchain4j.service.tool.DefaultToolExecutor} 原生吞 @Tool 异常→异常消息当工具结果
 * 返回（不抛），故 {@code ToolRecoverableException} 不透传至本步
 * （[[langchain4j-boot4-compat-findings]]：自纠正开箱即用）。
 */
class ToolExecutionStepTest {

    private final ToolCallExecutor executor = mock(ToolCallExecutor.class);
    private final ToolExecutionStep step = new ToolExecutionStep(executor);

    @Test
    void toolCall_fillsToolResults_proceeds() {
        when(executor.execute("计算 1+2*3")).thenReturn(List.of("7"));
        PipelineContext ctx = new PipelineContext("s1", "计算 1+2*3");
        ctx.setStandardQuery(StandardQuery.of("计算 1+2*3"));

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.toolResults()).containsExactly("7");
    }

    @Test
    void noTool_proceeds_withEmptyResults() {
        when(executor.execute(anyString())).thenReturn(List.of());
        PipelineContext ctx = new PipelineContext("s1", "你好");
        ctx.setStandardQuery(StandardQuery.of("你好"));

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.toolResults()).isEmpty();
    }

    @Test
    void usesStandardQuery_whenPresent() {
        when(executor.execute(eq("标准问题"))).thenReturn(List.of());
        PipelineContext ctx = new PipelineContext("s1", "原始");
        ctx.setStandardQuery(StandardQuery.of("标准问题"));

        step.process(ctx);

        verify(executor).execute(eq("标准问题"));
    }

    @Test
    void fallsBackToRawInput_whenStandardQueryMissing() {
        when(executor.execute(eq("原始"))).thenReturn(List.of());
        PipelineContext ctx = new PipelineContext("s1", "原始");

        step.process(ctx);

        verify(executor).execute(eq("原始"));
    }

    @Test
    void name_isToolExecutionStep() {
        assertThat(step.name()).isEqualTo("ToolExecutionStep");
    }

    @Test
    void process_recordsToolSuccessAndFailureCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        ToolCallExecutor okExec = mock(ToolCallExecutor.class);
        ToolCallExecutor failExec = mock(ToolCallExecutor.class);
        when(okExec.execute(anyString())).thenReturn(List.of("7"));
        when(failExec.execute(anyString())).thenThrow(new ToolCircuitOpenException("flaky"));
        ToolExecutionStep okStep = new ToolExecutionStep(okExec, metrics);
        ToolExecutionStep failStep = new ToolExecutionStep(failExec, metrics);

        okStep.process(new PipelineContext("ok", "计算 1+2"));
        failStep.process(new PipelineContext("f", "1/0"));

        assertThat(registry.counter("agent.tool", "success", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.tool", "success", "false").count()).isEqualTo(1.0);
    }
}
