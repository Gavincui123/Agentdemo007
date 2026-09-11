package com.agentdemo007.resilience;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * 滑动窗口熔断器（主备容灾·模型级熔断核心）。
 *
 * <p>三态状态机（与 {@link CircuitBreaker} 同构，CLOSED 计数语义改为滑动窗口）：
 * <ul>
 *   <li>CLOSED：正常放行；记录失败时把时间戳入窗、淘汰出窗旧值，<b>窗口内失败数 ≥ 阈值</b>→OPEN。
 *       <b>偶发成功不清窗</b>（仅时间衰减）——区别于 {@link CircuitBreaker} 的"连续失败"计数
 *       （后者一次成功即清零，会被"间歇性成功"掩盖系统性故障）。窗口失败数随时间滚动衰减。</li>
 *   <li>OPEN：{@link #allowRequest()} 返回 false（快速失败，不发起调用）；冷却到期后下次放行转 HALF_OPEN。</li>
 *   <li>HALF_OPEN：放行探针；探针成功→CLOSED 清窗恢复，探针失败→重开 OPEN 重计冷却。</li>
 * </ul>
 *
 * <p>时钟可注入（{@link LongSupplier}）保证可测；阈值/窗口/冷却可配。本组件独立单测；
 * per-model 接线由 {@link ModelCircuitBreaker}（镜像 {@link ToolCircuitBreaker} 的 per-key 注册表）负责。
 *
 * <p>线程安全：所有读写方法 {@code synchronized}——{@link ModelCircuitBreaker} 把本组件按 modelId
 * 装入 {@code ConcurrentHashMap}，同 modelId 的多请求线程共享同一实例并竞态入窗/淘汰，须同步防丢失更新
 * （否则窗口计数漂移、熔断该开不开）。
 */
public class WindowedCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long windowMs;
    private final long cooldownMs;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    /** 滑动窗口内的失败时间戳（单调时序，队首最旧）。 */
    private final Deque<Long> failures = new ArrayDeque<>();
    private long openedAt = 0L;

    public WindowedCircuitBreaker(int failureThreshold, long windowMs, long cooldownMs, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.windowMs = windowMs;
        this.cooldownMs = cooldownMs;
        this.clock = clock;
    }

    /** 是否放行本次请求。OPEN 且未过冷却返回 false（快速失败）；过期转 HALF_OPEN 放行探针；其余放行。 */
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

    /**
     * 记录成功：HALF_OPEN 探针成功 → 恢复 CLOSED 并清窗（重新计满）；CLOSED 态<b>不清窗</b>
     * （偶发成功不清零，仅时间衰减）。OPEN 态不应被调用（allowRequest 已拦），防御性 no-op。
     */
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            failures.clear();
            state = State.CLOSED;
        }
        // CLOSED：不清窗（用户语义）；OPEN：不可达
    }

    /**
     * 记录失败：CLOSED 入窗 + 淘汰旧值，窗内数 ≥ 阈值 → OPEN；HALF_OPEN 探针失败 → 重开 OPEN 重计冷却；
     * OPEN 防御性 no-op（allowRequest 已拦，不重复延冷却）。
     */
    public synchronized void recordFailure() {
        long now = clock.getAsLong();
        switch (state) {
            case CLOSED:
                evict(now);
                failures.addLast(now);
                if (failures.size() >= failureThreshold) {
                    state = State.OPEN;
                    openedAt = now;
                }
                break;
            case HALF_OPEN:
                state = State.OPEN;
                openedAt = now;
                break;
            default: // OPEN
                break;
        }
    }

    public synchronized State state() {
        return state;
    }

    /** 淘汰窗口外旧失败时间戳（严格小于窗口下界即出窗）。 */
    private void evict(long now) {
        long lowerBound = now - windowMs;
        while (!failures.isEmpty() && failures.peekFirst() < lowerBound) {
            failures.pollFirst();
        }
    }
}
