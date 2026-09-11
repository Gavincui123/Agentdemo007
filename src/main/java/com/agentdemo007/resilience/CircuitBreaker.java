package com.agentdemo007.resilience;

import java.util.function.LongSupplier;

/**
 * 熔断器（Phase 5·韧性层）。
 *
 * <p>三态状态机：
 * <ul>
 *   <li>CLOSED：正常放行；连续失败达阈值 → 转 OPEN。成功重置连续失败计数。</li>
 *   <li>OPEN：快速失败（{@link #allowRequest()} 返回 false，不发起调用）；冷却到期后下次放行转 HALF_OPEN。</li>
 *   <li>HALF_OPEN：放行探针；探针成功 → CLOSED 恢复，探针失败 → 重开 OPEN 重计冷却。</li>
 * </ul>
 *
 * <p>时钟可注入（{@link LongSupplier}）保证可测；连续失败阈值 + 冷却时长可配。
 * 本组件独立单测；per-model 故障转移接线 DEFERRED（单一全局熔断会阻断向备选模型切换）。
 * 快速失败以布尔信号表达；熔断异常类留到接线时再测驱动。
 *
 * <p>线程安全：所有读写方法 {@code synchronized}——Phase 17 {@link ToolCircuitBreaker} 把本组件
 * 按 toolName 装入 {@code ConcurrentHashMap}，同 toolName 的多请求线程共享同一实例并竞态
 * {@code ++consecutiveFailures}，须同步防丢失更新（否则熔断该开不开）。
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMs;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAt = 0L;

    public CircuitBreaker(int failureThreshold, long cooldownMs, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.clock = clock;
    }

    /** 是否放行本次请求。OPEN 且未过冷却返回 false（快速失败）；其余放行。 */
    public synchronized boolean allowRequest() {
        switch (state) {
            case OPEN:
                if (clock.getAsLong() >= openedAt + cooldownMs) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            case HALF_OPEN:
                return true;
            default: // CLOSED
                return true;
        }
    }

    /** 记录成功：重置连续失败计数；HALF_OPEN 探针成功 → 恢复 CLOSED。 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
        }
    }

    /** 记录失败：CLOSED 累计达阈值 → 开 OPEN；HALF_OPEN 探针失败 → 重开 OPEN 重计冷却。 */
    public synchronized void recordFailure() {
        switch (state) {
            case CLOSED:
                if (++consecutiveFailures >= failureThreshold) {
                    state = State.OPEN;
                    openedAt = clock.getAsLong();
                }
                break;
            case HALF_OPEN:
                state = State.OPEN;
                openedAt = clock.getAsLong();
                break;
            default: // OPEN：快速失败已拦，此处不应被调用；防御性重计冷却
                openedAt = clock.getAsLong();
                break;
        }
    }

    public synchronized State state() {
        return state;
    }
}
