package com.agentdemo007.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-tool 熔断器单测（Phase 17·T73）。
 *
 * <p>验 per-tool 隔离（toolA 熔断不波及 toolB）+ 三态流转（CLOSED→OPEN→HALF_OPEN→CLOSED），
 * 复用 Phase 5 {@link CircuitBreaker} 三态语义 + 可注入时钟。per-tool 维度独立计数是本组件
 * 相对 Phase 5 单实例 {@code CircuitBreaker} 的核心增量。
 */
class ToolCircuitBreakerTest {

    private static final int THRESHOLD = 3;
    private static final long COOLDOWN_MS = 1000L;

    @Test
    void perToolIsolation_failuresOnToolADoNotOpenToolB() {
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(THRESHOLD, COOLDOWN_MS, () -> 0L);

        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure("calc");
        }
        // toolA 连续失败达阈值 → OPEN，放行被拦
        assertThat(breaker.state("calc")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allow("calc")).isFalse();

        // toolB 未受 toolA 故障影响——per-tool 隔离
        assertThat(breaker.state("search")).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.allow("search")).isTrue();
    }

    @Test
    void open_recoversAfterCooldownAndProbeSuccess() {
        AtomicLong clock = new AtomicLong(0L);
        ToolCircuitBreaker breaker = new ToolCircuitBreaker(THRESHOLD, COOLDOWN_MS, clock::get);

        for (int i = 0; i < THRESHOLD; i++) {
            breaker.recordFailure("calc");
        }
        assertThat(breaker.state("calc")).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allow("calc")).isFalse();

        // 冷却到期 → 转 HALF_OPEN 放行探针
        clock.set(COOLDOWN_MS);
        assertThat(breaker.allow("calc")).isTrue();
        assertThat(breaker.state("calc")).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // 探针成功 → 恢复 CLOSED
        breaker.recordSuccess("calc");
        assertThat(breaker.state("calc")).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
