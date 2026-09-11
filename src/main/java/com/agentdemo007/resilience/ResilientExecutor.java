package com.agentdemo007.resilience;

import java.util.Random;

/**
 * 重试执行模板（Phase 5·韧性层 §5.8 四层异常分诊）。
 *
 * <p>把一次（单模型）执行 {@code action} 包进退避重试循环：
 * <ul>
 *   <li>分诊为 {@link Decision#RETRY} 且未达 {@link RetryPolicy#maxAttempts()} → 退避睡眠后重试；</li>
 *   <li>分诊为 {@link Decision#FAIL} / {@link Decision#FEEDBACK_TO_LLM} / {@link Decision#AUDIT_AND_FAIL}
 *       → 立即重抛，不睡眠（交由上层处理）；</li>
 *   <li>达到上限仍失败 → 重抛末次异常（交由 FailoverExecutor 切备选模型故障转移）。</li>
 * </ul>
 *
 * <p>退避计算：提供方 Retry-After 提示（{@code retryAfterMs >= 0}）被原样遵循、不再抖动；
 * 否则按 {@link BackoffStrategy#baseDelayMs} 取指数基数，开启抖动时落 [0, base]（全抖动）。
 *
 * <p>测试缝：{@link Sleeper}（不真实睡眠、记录延迟）与 {@link Random}（确定性抖动）可注入。
 *
 * <p>分层：本模板包裹"单模型"执行；重试耗尽后由 {@code FailoverExecutor} 切换备选模型——
 * 即重试=同模型退避，故障转移=换模型，两者正交。
 */
public class ResilientExecutor {

    private final ExceptionTriage triage;
    private final BackoffStrategy backoff;
    private final Sleeper sleeper;
    private final Random random;

    public ResilientExecutor(ExceptionTriage triage, BackoffStrategy backoff, Sleeper sleeper, Random random) {
        this.triage = triage;
        this.backoff = backoff;
        this.sleeper = sleeper;
        this.random = random;
    }

    /**
     * 执行可重试动作。
     *
     * @param action 单模型执行动作，可抛 RuntimeException
     * @param policy 重试策略（最大次数 / 退避基数 / 倍数 / 上限 / 抖动开关）
     * @return 动作返回值
     * @throws RuntimeException 不可重试或耗尽时重抛末次异常
     */
    public <T> T execute(RetryAction<T> action, RetryPolicy policy) {
        for (int attempt = 0; attempt < policy.maxAttempts(); attempt++) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                TriageResult result = triage.triage(e);
                boolean canRetry = result.decision() == Decision.RETRY
                        && attempt + 1 < policy.maxAttempts();
                if (!canRetry) {
                    throw e;
                }
                sleeper.sleepMs(computeDelay(result, attempt, policy));
            }
        }
        // 循环末位恒重抛（attempt==maxAttempts-1 时 canRetry 必为 false），此处不可达
        throw new IllegalStateException("重试循环异常退出");
    }

    private long computeDelay(TriageResult result, int attempt, RetryPolicy policy) {
        if (result.retryAfterMs() >= 0) {
            return result.retryAfterMs();
        }
        long base = backoff.baseDelayMs(attempt, policy);
        if (!policy.jitter()) {
            return base;
        }
        return (long) (random.nextDouble() * (base + 1));
    }
}
