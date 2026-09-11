package com.agentdemo007.resilience;

/**
 * 重试策略配置（Phase 5·韧性层）。
 *
 * <p>对齐 §5.8：RETRYABLE_TRANSIENT 异常按指数退避 + 全抖动透明重试，上限 {@code maxAttempts}（默认 3）。
 * <ul>
 *   <li>{@code initialBackoffMs} — 首次退避基数（默认 100ms）。</li>
 *   <li>{@code multiplier} — 指数倍率（默认 2.0）。</li>
 *   <li>{@code maxBackoffMs} — 单次退避封顶（默认 10s）。</li>
 *   <li>{@code jitter} — 是否施加全抖动（{@link ResilientExecutor} 执行时据此削减为 [0,base] 随机值）。</li>
 * </ul>
 *
 * <p>{@link #noRetry()} 供不需要退避重试的场景（如 FailoverExecutor 默认无重试兼容旧调用）。
 */
public record RetryPolicy(int maxAttempts, long initialBackoffMs, double multiplier,
                          long maxBackoffMs, boolean jitter) {

    /** 默认策略：上限 3 次，100ms 起，×2，封顶 10s，全抖动。 */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, 100L, 2.0, 10_000L, true);
    }

    /** 不重试：仅执行 1 次（退避重试关闭）。 */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, 0L, 1.0, 0L, false);
    }
}
