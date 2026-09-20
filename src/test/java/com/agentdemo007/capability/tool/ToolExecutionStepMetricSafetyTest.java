package com.agentdemo007.capability.tool;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolExecutionStep} 降级路径指标安全测评（code-review #4·② Slice 3 迁 ToolCallExecutor）。
 *
 * <p>熔断 catch 内调 {@code metrics.recordToolCircuitOpen}/{@code recordTool} 后才
 * {@code return ShortCircuit(TOOL_FAILURE)}。若指标调用抛（Micrometer 对非法 tag/重复不一致标签会抛），
 * 异常上抛 → ②降级失败，{@code ShortCircuit} 永不返回。须吞掉指标异常，保证降级短路仍落地。
 *
 * <p>注入 {@code recordToolCircuitOpen} 抛异常的 metrics 替身 + 抛 {@link ToolCircuitOpenException}
 * 的 stub 执行器，断言 {@code process} 仍返回 {@code ShortCircuit(TOOL_FAILURE)} 而非上抛。
 */
class ToolExecutionStepMetricSafetyTest {

    /** 指标记录抛异常的替身——模拟 Micrometer 在异常 tag 时抛。 */
    private AgentMetrics throwingMetrics() {
        return new AgentMetrics(new SimpleMeterRegistry()) {
            @Override
            public void recordToolCircuitOpen(String toolName) {
                throw new RuntimeException("metric boom");
            }
        };
    }

    /** stub 执行器：直接抛 ToolCircuitOpenException（模拟断路器 OPEN）。 */
    private ToolCallExecutor throwingExecutor() {
        return new ToolCallExecutor(null, null, 0, List.of(), Map.of(), Map.of()) {
            @Override
            public ToolTurn execute(String query) {
                throw new ToolCircuitOpenException("flaky");
            }
        };
    }

    @Test
    void circuitOpen_metricThrows_stillShortCircuits_notPropagated() {
        ToolExecutionStep step = new ToolExecutionStep(throwingExecutor(), throwingMetrics());
        PipelineContext ctx = new PipelineContext("s1", "查一下订单");

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.ShortCircuit.class);
        assertThat(((StepOutcome.ShortCircuit) outcome).scenario())
                .isEqualTo(DegradationScenario.TOOL_FAILURE);
    }
}
