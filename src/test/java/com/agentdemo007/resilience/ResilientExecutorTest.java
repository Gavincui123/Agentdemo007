package com.agentdemo007.resilience;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 重试执行模板测试（Phase 5·韧性层）。
 *
 * <p>验证：瞬态异常退避重试直至成功；不可重试异常立即重抛（不睡眠）；
 * 重试耗尽重抛末次异常（交由 FailoverExecutor 切备选）；全抖动延迟落在 [0,base]；
 * 提供方 Retry-After 提示被遵循且不再抖动。
 *
 * <p>注入 {@link CapturingSleeper}（不真实睡眠、记录延迟）与 {@link FixedRandom}（确定性抖动）保证可测。
 */
class ResilientExecutorTest {

    private final ExceptionTriage triage = new ExceptionTriage();
    private final BackoffStrategy backoff = new BackoffStrategy();
    private final RetryPolicy policy = RetryPolicy.defaults(); // 3 次，100/200/400 base

    @Test
    void successFirstTry_noSleep() {
        CapturingSleeper sleeper = new CapturingSleeper();
        ResilientExecutor exec = new ResilientExecutor(triage, backoff, sleeper, new FixedRandom(0.5));

        String result = exec.execute(() -> "ok", policy);

        assertThat(result).isEqualTo("ok");
        assertThat(sleeper.delays).isEmpty();
    }

    @Test
    void transientThenSuccess_retriesWithBackoff() {
        CapturingSleeper sleeper = new CapturingSleeper();
        int[] calls = {0};
        ResilientExecutor exec = new ResilientExecutor(triage, backoff, sleeper, new FixedRandom(0.5));

        String result = exec.execute(() -> {
            calls[0]++;
            if (calls[0] < 3) throw new TransientException("429");
            return "ok";
        }, policy);

        assertThat(result).isEqualTo("ok");
        assertThat(calls[0]).isEqualTo(3);
        assertThat(sleeper.delays).hasSize(2); // 3 次尝试 → 2 次退避
        // 全抖动 random=0.5：attempt0 base=100→~50；attempt1 base=200→~100
        assertThat(sleeper.delays.get(0)).isBetween(0L, 100L);
        assertThat(sleeper.delays.get(1)).isBetween(0L, 200L);
    }

    @Test
    void nonRetryable_notRetried() {
        CapturingSleeper sleeper = new CapturingSleeper();
        ResilientExecutor exec = new ResilientExecutor(triage, backoff, sleeper, new FixedRandom(0.5));

        assertThatThrownBy(() -> exec.execute(() -> { throw new NonRetryableException("403"); }, policy))
                .isInstanceOf(NonRetryableException.class);
        assertThat(sleeper.delays).isEmpty();
    }

    @Test
    void exhausted_rethrowsLastException() {
        CapturingSleeper sleeper = new CapturingSleeper();
        ResilientExecutor exec = new ResilientExecutor(triage, backoff, sleeper, new FixedRandom(0.5));

        assertThatThrownBy(() -> exec.execute(() -> { throw new TransientException("always 429"); }, policy))
                .isInstanceOf(TransientException.class);
        assertThat(sleeper.delays).hasSize(2); // maxAttempts=3 → 2 次退避后耗尽
    }

    @Test
    void retryAfterHint_honoredWithoutJitter() {
        CapturingSleeper sleeper = new CapturingSleeper();
        ResilientExecutor exec = new ResilientExecutor(triage, backoff, sleeper, new FixedRandom(0.5));

        assertThatThrownBy(() -> exec.execute(() -> { throw new TransientException("429", 5000L); }, policy))
                .isInstanceOf(TransientException.class);
        // 遵循提供方 Retry-After=5000ms，不再抖动
        assertThat(sleeper.delays).hasSize(2);
        assertThat(sleeper.delays).allMatch(d -> d == 5000L);
    }

    @Test
    void fullJitter_boundsAreZeroToBase() {
        // random=0.0 → 抖动=0
        CapturingSleeper sleeper0 = new CapturingSleeper();
        ResilientExecutor exec0 = new ResilientExecutor(triage, backoff, sleeper0, new FixedRandom(0.0));
        assertThatThrownBy(() -> exec0.execute(() -> { throw new TransientException("429"); }, policy))
                .isInstanceOf(TransientException.class);
        assertThat(sleeper0.delays).allMatch(d -> d == 0L);

        // random=0.999 → 抖动接近 base（attempt0 base=100）
        CapturingSleeper sleeperHi = new CapturingSleeper();
        ResilientExecutor execHi = new ResilientExecutor(triage, backoff, sleeperHi, new FixedRandom(0.999));
        assertThatThrownBy(() -> execHi.execute(() -> { throw new TransientException("429"); }, policy))
                .isInstanceOf(TransientException.class);
        assertThat(sleeperHi.delays.get(0)).isLessThanOrEqualTo(100L);
    }

    // ---- 测试缝 ----

    static class CapturingSleeper implements Sleeper {
        final List<Long> delays = new ArrayList<>();
        @Override
        public void sleepMs(long millis) {
            delays.add(millis);
        }
    }

    static class FixedRandom extends Random {
        private final double value;
        FixedRandom(double value) {
            super();
            this.value = value;
        }
        @Override
        public double nextDouble() {
            return value;
        }
    }
}
