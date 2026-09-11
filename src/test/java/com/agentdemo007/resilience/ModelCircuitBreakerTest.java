package com.agentdemo007.resilience;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * per-model 熔断注册表单测（镜像 {@link ToolCircuitBreakerTest} 的 per-key 隔离语义）。
 *
 * <p>验证：modelA 熔断不影响 modelB；未注册的 modelId 首次 allow 返回 true（惰性建实例）；三态读写正确路由。
 */
class ModelCircuitBreakerTest {

    private final long[] t = {0L};
    private final LongSupplier clock = () -> t[0];

    @Test
    void perModelIsolation() {
        ModelCircuitBreaker breakers = new ModelCircuitBreaker(3, 100L, 50L, clock);
        // modelA 连续 3 次失败 → OPEN
        breakers.recordFailure("siliconflow-large");
        breakers.recordFailure("siliconflow-large");
        breakers.recordFailure("siliconflow-large");
        assertThat(breakers.state("siliconflow-large")).isEqualTo(WindowedCircuitBreaker.State.OPEN);
        assertThat(breakers.allow("siliconflow-large")).isFalse();
        // modelB 未受影响
        assertThat(breakers.allow("sensenova-large")).isTrue();
        assertThat(breakers.state("sensenova-large")).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
    }

    @Test
    void recordSuccessRecoverFromHalfOpen() {
        ModelCircuitBreaker breakers = new ModelCircuitBreaker(2, 100L, 50L, clock);
        breakers.recordFailure("m");
        breakers.recordFailure("m"); // OPEN
        t[0] = 50L;
        breakers.allow("m"); // → HALF_OPEN
        breakers.recordSuccess("m");
        assertThat(breakers.state("m")).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
        assertThat(breakers.allow("m")).isTrue();
    }
}
