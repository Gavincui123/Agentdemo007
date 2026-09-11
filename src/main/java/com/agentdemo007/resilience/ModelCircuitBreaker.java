package com.agentdemo007.resilience;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-model 熔断注册表（主备容灾·模型级断路器接线）。
 *
 * <p>按 modelId 维度独立计数的滑动窗口三态熔断（{@link WindowedCircuitBreaker}），镜像
 * {@link ToolCircuitBreaker}（Phase 17·per-tool）的 per-key 注册表结构。单一主模型故障熔断不致
 * 阻断向备选模型切换——主 OPEN 时 FailoverExecutor 抛 {@code CircuitOpenException} 切备，备独立计数。
 *
 * <p>per-model 隔离：modelA 熔断不影响 modelB（区别于 Phase 5 单实例 {@link CircuitBreaker}——
 * 单一全局熔断会阻断向备选模型切换，故 per-model 接线）。
 */
public class ModelCircuitBreaker {

    private final int failureThreshold;
    private final long windowMs;
    private final long cooldownMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, WindowedCircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ModelCircuitBreaker(int failureThreshold, long windowMs, long cooldownMs, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.windowMs = windowMs;
        this.cooldownMs = cooldownMs;
        this.clock = clock;
    }

    private WindowedCircuitBreaker breakerFor(String modelId) {
        return breakers.computeIfAbsent(modelId,
                k -> new WindowedCircuitBreaker(failureThreshold, windowMs, cooldownMs, clock));
    }

    /** 是否放行 modelId 的本次调用（OPEN 且未过冷却返回 false，快速失败不发起模型调用）。 */
    public boolean allow(String modelId) {
        return breakerFor(modelId).allowRequest();
    }

    /** 记录 modelId 调用成功（HALF_OPEN 探针成功→恢复 CLOSED；CLOSED 不清窗）。 */
    public void recordSuccess(String modelId) {
        breakerFor(modelId).recordSuccess();
    }

    /** 记录 modelId 调用失败（CLOSED 窗内失败数达阈值→OPEN；HALF_OPEN 探针失败→重开 OPEN）。 */
    public void recordFailure(String modelId) {
        breakerFor(modelId).recordFailure();
    }

    /** modelId 当前熔断状态（可观测/测试用）。 */
    public WindowedCircuitBreaker.State state(String modelId) {
        return breakerFor(modelId).state();
    }
}
