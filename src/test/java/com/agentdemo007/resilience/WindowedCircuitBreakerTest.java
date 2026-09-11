package com.agentdemo007.resilience;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 滑动窗口熔断器单测（主备容灾·模型级熔断核心）。
 *
 * <p>语义（用户选定）：60s 滚动窗口累计<b>失败数</b>≥阈值→OPEN；冷却→HALF_OPEN 探针；
 * 探针成功→CLOSED 清窗；探针失败→重开 OPEN；<b>CLOSED 态偶发成功不清窗</b>（仅时间衰减）。
 * 时钟可注入（LongSupplier）保证可测——镜像现有 {@link CircuitBreaker} 的可测缝。
 */
class WindowedCircuitBreakerTest {

    private long[] clock(int initial) { return new long[]{initial}; }
    private LongSupplier src(long[] t) { return () -> t[0]; }

    @Test
    void freshBreakerIsClosedAndAllows() {
        long[] t = clock(0);
        WindowedCircuitBreaker cb = new WindowedCircuitBreaker(3, 100L, 50L, src(t));
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void failuresBelowThresholdStayClosed() {
        long[] t = clock(0);
        WindowedCircuitBreaker cb = new WindowedCircuitBreaker(3, 100L, 50L, src(t));
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
        assertThat(cb.allowRequest()).isTrue();
    }

    @Test
    void failuresAtThresholdOpenAndFastFail() {
        long[] t = clock(0);
        WindowedCircuitBreaker cb = new WindowedCircuitBreaker(3, 100L, 50L, src(t));
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse(); // 冷却未到，快速失败
    }

    @Test
    void windowAgesOutOldFailures() {
        // 阈值 3，窗口 100ms：t=0 记 2 次失败 → 推进到 t=200（旧失败出窗）→ 再记 1 次 → 窗内仅 1 → 仍 CLOSED
        long[] t = clock(0);
        WindowedCircuitBreaker cb = new WindowedCircuitBreaker(3, 100L, 50L, src(t));
        cb.recordFailure();
        cb.recordFailure();
        t[0] = 200L; // 窗口滚动，t=0 的 2 次已衰减
        cb.recordFailure(); // 窗内现在仅这 1 次
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
        // 再补 2 次到达阈值 → OPEN（证明旧失败确已出窗，否则 1+2=3 也 OPEN 但语义是"窗内 3"才开）
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.OPEN);
    }

    @Test
    void openTransitionsToHalfOpenAfterCooldown() {
        long[] t = clock(0);
        WindowedCircuitBreaker cb = new WindowedCircuitBreaker(2, 100L, 50L, src(t));
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.OPEN);
        t[0] = 50L; // 冷却到期
        assertThat(cb.allowRequest()).isTrue(); // 放行探针
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenProbeSuccessClosesAndClearsWindow() {
        long[] t = clock(0);
        WindowedCircuitBreaker cb = new WindowedCircuitBreaker(2, 100L, 50L, src(t));
        cb.recordFailure();
        cb.recordFailure(); // OPEN
        t[0] = 50L;
        cb.allowRequest(); // → HALF_OPEN
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
        // 清窗：探针成功后，原 2 次失败已清，需重新累计才再 OPEN
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenProbeFailureReopens() {
        long[] t = clock(0);
        WindowedCircuitBreaker cb = new WindowedCircuitBreaker(2, 100L, 50L, src(t));
        cb.recordFailure();
        cb.recordFailure(); // OPEN
        t[0] = 50L;
        cb.allowRequest(); // → HALF_OPEN
        cb.recordFailure(); // 探针失败
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.OPEN);
        assertThat(cb.allowRequest()).isFalse(); // 重计冷却，未到期
    }

    @Test
    void closedSuccessDoesNotClearWindow() {
        // 用户关键语义：偶发成功不清零。2 次失败后成功 1 次，再 1 次失败仍应 OPEN（若清零则需重新 3 次）
        long[] t = clock(0);
        WindowedCircuitBreaker cb = new WindowedCircuitBreaker(3, 100L, 50L, src(t));
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess(); // 偶发成功——不清窗
        cb.recordFailure(); // 窗内累计 3（2 旧 + 1 新，成功不计入也不清零）
        assertThat(cb.state()).isEqualTo(WindowedCircuitBreaker.State.OPEN);
    }
}
