package com.agentdemo007.capability.rag;

import com.agentdemo007.resilience.ModelCircuitBreaker;

import java.util.function.Supplier;

/**
 * RAG 外部通道熔断守卫（嵌入/重排共享）：复用 {@link ModelCircuitBreaker} 滑动窗口熔断，
 * 把"provider 故障期每轮吃满 HTTP 超时"变成"首次失败后快速失败 + 周期性半开探测"。
 *
 * <p>背景（实测事故）：SiliconFlow 嵌入/重排接口故障期，每轮查询的稠密检索 + 重排各吃一次
 * 读超时（合计 ~60s），首轮请求延迟 70s+。本守卫单次失败即开断路器（阈值 1）——RAG 的稠密/重排
 * 是<b>可选增强通道</b>，本地 BM25 稀疏检索是恒可用的基线，单次失败即快速降级的代价最小；
 * 冷却期后半开探测自动恢复，健康期零影响（recordSuccess 关闭断路器）。
 *
 * <p>线程安全：{@link ModelCircuitBreaker} 内部保证；本类无状态透传。
 */
public final class CircuitBreakerGuard {

    private final ModelCircuitBreaker breaker;
    private final String key;
    private final String channelName;

    /**
     * @param channelName 通道名（日志/排查用，如 "embedding"/"reranker"）
     * @param windowMs    失败计数滑动窗口
     * @param cooldownMs  开断后的冷却期（期内直接快速失败，期满半开探测）
     */
    public CircuitBreakerGuard(String channelName, long windowMs, long cooldownMs) {
        this.breaker = new ModelCircuitBreaker(1, windowMs, cooldownMs, System::currentTimeMillis);
        this.key = channelName;
        this.channelName = channelName;
    }

    /** 执行一次外部调用：熔断开启 → 立即抛（调用方降级本地通道）；成功记成功、异常记失败。 */
    public <T> T call(Supplier<T> action) {
        if (!breaker.allow(key)) {
            throw new IllegalStateException(
                    channelName + " 熔断开启中（近期连续失败，快速降级本地通道）");
        }
        try {
            T result = action.get();
            breaker.recordSuccess(key);
            return result;
        } catch (RuntimeException e) {
            breaker.recordFailure(key);
            throw e;
        }
    }
}
