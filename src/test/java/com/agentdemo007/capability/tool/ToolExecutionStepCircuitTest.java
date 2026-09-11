package com.agentdemo007.capability.tool;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolExecutionStep} 熔断收口单测（Phase 17·T75·② Slice 3 迁 ToolCallExecutor）。
 *
 * <p>验 {@link ToolCallExecutor} 抛 {@link ToolCircuitOpenException}（断路器 OPEN）时，
 * {@link ToolExecutionStep#process} 收口为 {@link StepOutcome.ShortCircuit}(TOOL_FAILURE)
 * 话术短路（HTTP 200，零 LLM，①话术短路 + ④统一收口），而非向上抛出。
 */
class ToolExecutionStepCircuitTest {

    @Test
    void circuitOpenException_shortCircuitsToToolFailure() {
        // stub 执行器：直接抛 ToolCircuitOpenException（模拟断路器 OPEN）
        ToolCallExecutor throwingExecutor = new ToolCallExecutor(null, null, 0, List.of(), Map.of()) {
            @Override
            public List<String> execute(String query) {
                throw new ToolCircuitOpenException("flaky");
            }
        };
        ToolExecutionStep step = new ToolExecutionStep(throwingExecutor);
        PipelineContext ctx = new PipelineContext("s1", "查一下订单"); // standardQuery 缺失→resolveQuery 回退 rawInput

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.ShortCircuit.class);
        StepOutcome.ShortCircuit sc = (StepOutcome.ShortCircuit) outcome;
        assertThat(sc.scenario()).isEqualTo(DegradationScenario.TOOL_FAILURE);
    }
}
