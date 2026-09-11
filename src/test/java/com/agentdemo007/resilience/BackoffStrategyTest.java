package com.agentdemo007.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 指数退避策略测试（Phase 5·韧性层）。
 *
 * <p>验证：base 延迟按 {@code initial * multiplier^attempt} 指数增长，
 * 且被 {@code maxBackoffMs} 封顶。本类只算确定性 base；全抖动由 ResilientExecutor 施加。
 */
class BackoffStrategyTest {

    private final BackoffStrategy strategy = new BackoffStrategy();
    private final RetryPolicy policy = RetryPolicy.defaults();

    @Test
    void baseDelay_growsExponentially() {
        assertThat(strategy.baseDelayMs(0, policy)).isEqualTo(100);
        assertThat(strategy.baseDelayMs(1, policy)).isEqualTo(200);
        assertThat(strategy.baseDelayMs(2, policy)).isEqualTo(400);
        assertThat(strategy.baseDelayMs(3, policy)).isEqualTo(800);
    }

    @Test
    void baseDelay_cappedAtMax() {
        // attempt 20 → 100 * 2^20 远超 maxBackoffMs(10000) → 封顶
        assertThat(strategy.baseDelayMs(20, policy)).isEqualTo(policy.maxBackoffMs());
    }

    @Test
    void baseDelay_respectsCustomInitial() {
        RetryPolicy custom = new RetryPolicy(5, 500, 3.0, 60_000, true);
        assertThat(strategy.baseDelayMs(0, custom)).isEqualTo(500);
        assertThat(strategy.baseDelayMs(1, custom)).isEqualTo(1500); // 500 * 3
        assertThat(strategy.baseDelayMs(2, custom)).isEqualTo(4500); // 500 * 9
    }
}
