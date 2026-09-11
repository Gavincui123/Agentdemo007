package com.agentdemo007.resilience;

/**
 * 指数退避策略（Phase 5·韧性层）。
 *
 * <p>计算确定性 base 延迟：{@code initial * multiplier^attempt}，封顶于 {@code maxBackoffMs}。
 * 全抖动（[0, base] 随机化）由 {@link ResilientExecutor} 在执行时施加，本类只产出确定性基数，
 * 便于单测断言与可观测埋点。
 */
public class BackoffStrategy {

    /**
     * 第 {@code attempt} 次重试（从 0 起）的退避基数（毫秒），指数增长且封顶。
     *
     * @param attempt 重试序号，0 表示首次失败后的第一次退避
     * @param policy  重试策略
     * @return 退避基数毫秒，不超过 {@code policy.maxBackoffMs()}
     */
    public long baseDelayMs(int attempt, RetryPolicy policy) {
        double raw = policy.initialBackoffMs() * Math.pow(policy.multiplier(), attempt);
        long capped = (long) Math.min(raw, policy.maxBackoffMs());
        return Math.max(capped, 0L);
    }
}
