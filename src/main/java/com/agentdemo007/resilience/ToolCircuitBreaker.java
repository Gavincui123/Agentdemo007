package com.agentdemo007.resilience;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-tool 熔断器（Phase 17·工具调用断路器，T73）。
 *
 * <p>按 toolName 维度独立计数的三态熔断（CLOSED/OPEN/HALF_OPEN），复用 Phase 5
 * {@link CircuitBreaker} 三态语义 + 可注入 {@link LongSupplier} 时钟。单一外部工具故障不致
 * 反复触发自纠正/死循环——OPEN 后工具调用前置 {@link #allow(String)} 返回 false →
 * 抛 {@link ToolCircuitOpenException}（T74 接线）→ {@code ToolExecutionStep} 收口
 * {@code ShortCircuit(TOOL_FAILURE)}（零 LLM，①话术短路 + ②每步降级不阻塞主链路）。
 *
 * <p>per-tool 隔离：toolA 熔断不影响 toolB（区别于 Phase 5 单实例 {@link CircuitBreaker}——
 * 单一全局熔断会阻断向备选工具/模型切换）。
 */
public class ToolCircuitBreaker {

    private final int failureThreshold;
    private final long cooldownMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ToolCircuitBreaker(int failureThreshold, long cooldownMs, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
        this.clock = clock;
    }

    private CircuitBreaker breakerFor(String toolName) {
        return breakers.computeIfAbsent(toolName,
                k -> new CircuitBreaker(failureThreshold, cooldownMs, clock));
    }

    /** 是否放行 toolName 的本次调用（OPEN 且未过冷却返回 false，快速失败不发起工具执行）。 */
    public boolean allow(String toolName) {
        return breakerFor(toolName).allowRequest();
    }

    /** 记录 toolName 调用成功（HALF_OPEN 探针成功→恢复 CLOSED；CLOSED 重置失败计数）。 */
    public void recordSuccess(String toolName) {
        breakerFor(toolName).recordSuccess();
    }

    /** 记录 toolName 调用失败（CLOSED 累计达阈值→OPEN；HALF_OPEN 探针失败→重开 OPEN 重计冷却）。 */
    public void recordFailure(String toolName) {
        breakerFor(toolName).recordFailure();
    }

    /** toolName 当前熔断状态（可观测/测试用）。 */
    public CircuitBreaker.State state(String toolName) {
        return breakerFor(toolName).state();
    }
}
