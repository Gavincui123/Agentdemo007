package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.CircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.resilience.ToolErrorFeedback;
import com.agentdemo007.resilience.ToolRecoverableException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ToolExecutor} 断路器接入单测（Phase 17·T74）。
 *
 * <p>验 ToolExecutor 接入 per-tool 断路器后的行为：工具连续失败达阈值 → 熔断 OPEN →
 * 后续调用抛 {@link ToolCircuitOpenException}（不再执行工具、零自纠正，②每步降级不阻塞），
 * 区别于自纠正耗尽抛的 {@link ToolRecoverableException}。成功路径记 success 由
 * {@link ToolCircuitBreakerTest} 覆盖三态流转，本测聚焦接入边界。
 */
class ToolExecutorCircuitTest {

    private ToolExecutor executorWithFlakyTool(ToolCircuitBreaker breaker) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolDefinition(
                "flaky", "test flaky tool", List.of(),
                input -> Optional.of(new HashMap<String, Object>()),
                args -> { throw new RuntimeException("tool down"); }));
        ParamParser parser = new ParamParser(registry);
        SchemaValidator validator = new SchemaValidator();
        ToolErrorFeedback feedback = new ToolErrorFeedback(3);
        return new ToolExecutor(parser, validator, registry, feedback, Reparser.NONE, breaker);
    }

    @Test
    void repeatedToolFailure_opensBreaker_thenCircuitOpenException() {
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 1000L, () -> 0L);
        ToolExecutor executor = executorWithFlakyTool(breaker);

        // 前两次：工具异常 → ToolRecoverableException，断路器记 failure
        assertThatThrownBy(() -> executor.execute("x"))
                .isInstanceOf(ToolRecoverableException.class);
        assertThatThrownBy(() -> executor.execute("x"))
                .isInstanceOf(ToolRecoverableException.class);
        assertThat(breaker.state("flaky")).isEqualTo(CircuitBreaker.State.OPEN);

        // 第三次：熔断 OPEN → ToolCircuitOpenException（不再执行工具/自纠正）
        assertThatThrownBy(() -> executor.execute("x"))
                .isInstanceOf(ToolCircuitOpenException.class);
    }

    @Test
    void noBreaker_preservesExistingBehavior() {
        // 无断路器（null）→ 既有行为不变，不抛 ToolCircuitOpenException
        ToolExecutor executor = executorWithFlakyTool(null);
        assertThatThrownBy(() -> executor.execute("x"))
                .isInstanceOf(ToolRecoverableException.class);
        // 反复调用仍抛 ToolRecoverableException（无熔断介入）
        assertThatThrownBy(() -> executor.execute("x"))
                .isInstanceOf(ToolRecoverableException.class);
    }
}
