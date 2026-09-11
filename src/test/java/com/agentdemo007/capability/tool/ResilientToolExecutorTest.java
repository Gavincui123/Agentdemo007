package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.CircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ResilientToolExecutor} 测试——LC4j 执行原语（{@link ToolExecutor}）上的韧性装饰。
 *
 * <p>验证三态：① 成功→委托 + 记账不跳闸；② 失败→记账 + 重抛（达阈值开 OPEN）；
 * ③ OPEN→快速失败抛 {@link ToolCircuitOpenException}，不调底层 delegate。
 * delegate 用 lambda 桩（测装饰器逻辑，非测依赖），breaker 用真 {@link ToolCircuitBreaker}
 * + 注入时钟（复用，不手写/不 mock）。
 */
class ResilientToolExecutorTest {

    private static ToolExecutionRequest req(String name) {
        return ToolExecutionRequest.builder().name(name).arguments("{}").build();
    }

    @Test
    void success_delegatesAndReturns_breakerStaysClosed() {
        ToolExecutor delegate = (r, m) -> "6";
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(2, 30000, () -> 0);
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker);

        assertThat(exec.execute(req("triangleArea"), null)).isEqualTo("6");
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failure_recordsAndRethrows_opensBreakerAtThreshold() {
        ToolExecutor delegate = (r, m) -> { throw new RuntimeException("tool boom"); };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0); // 阈值1：一次失败即 OPEN
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker);

        assertThatThrownBy(() -> exec.execute(req("triangleArea"), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("tool boom");
        assertThat(breaker.state("triangleArea")).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void openBreaker_shortCircuits_delegateNotCalled() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutor delegate = (r, m) -> { calls.incrementAndGet(); return "x"; };
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(1, 30000, () -> 0);
        breaker.recordFailure("triangleArea"); // 预先 OPEN
        ResilientToolExecutor exec = new ResilientToolExecutor(delegate, breaker);

        assertThatThrownBy(() -> exec.execute(req("triangleArea"), null))
                .isInstanceOf(ToolCircuitOpenException.class);
        assertThat(calls.get()).isZero(); // 熔断 OPEN → 不调底层 delegate
    }
}
