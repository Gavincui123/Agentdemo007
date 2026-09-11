package com.agentdemo007.resilience;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断器测试（Phase 5·韧性层）。
 *
 * <p>验证三态状态机：CLOSED 正常放行；连续失败达阈值 → OPEN 快速失败（不计时）；
 * 冷却到期 → HALF_OPEN 放行探针；探针成功 → CLOSED 恢复，探针失败 → 重开 OPEN 重计冷却。
 * 成功重置连续失败计数（避免累积误开）。时钟可注入（{@link MutableClock}）保证确定性。
 *
 * <p>本组件独立单测；per-model 故障转移接线 DEFERRED（单一全局熔断会阻断备选模型切换）。
 * 快速失败以布尔信号表达；熔断异常类 {@code CircuitBreakerOpenException} 留到接线时再测驱动。
 */
class CircuitBreakerTest {

    private final MutableClock clock = new MutableClock();
    private final CircuitBreaker breaker = new CircuitBreaker(3, 1000L, clock);

    @Test
    void closed_allowsRequests() {
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void thresholdFailures_opensCircuit() {
        for (int i = 0; i < 3; i++) breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void successResetsFailureStreak() {
        breaker.recordFailure();  // 1
        breaker.recordFailure();  // 2
        breaker.recordSuccess();  // 重置连续失败计数
        breaker.recordFailure();  // 1
        breaker.recordFailure();  // 2 —— 未达阈值 3
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void open_fastFailsBeforeCooldown() {
        for (int i = 0; i < 3; i++) breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        // 未推进时钟 → 快速失败
        assertThat(breaker.allowRequest()).isFalse();
    }

    @Test
    void afterCooldown_transitionsToHalfOpen_andAllowsProbe() {
        for (int i = 0; i < 3; i++) breaker.recordFailure();
        clock.advance(1000L); // 冷却到期（边界含 >=）
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenSuccess_closesCircuit() {
        toHalfOpen();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();
    }

    @Test
    void halfOpenFailure_reopensCircuit() {
        toHalfOpen();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        // 重开即重计冷却，立即再次快速失败
        assertThat(breaker.allowRequest()).isFalse();
    }

    // ---- 辅助 ----

    private void toHalfOpen() {
        for (int i = 0; i < 3; i++) breaker.recordFailure();
        clock.advance(1000L);
        breaker.allowRequest(); // OPEN -> HALF_OPEN
    }

    static class MutableClock implements LongSupplier {
        long now = 0;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }
}
