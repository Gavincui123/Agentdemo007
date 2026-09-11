package com.agentdemo007.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 熔断器并发安全测评（Phase 17·code-review #1）。
 *
 * <p>Phase 5 {@link CircuitBreaker} 原为单线程单测设计（非 volatile 字段 + 非原子自增），
 * Phase 17 {@link ToolCircuitBreaker} 把它暴露给 Spring 多请求并发——同 toolName 的多线程
 * 会共享同一 {@link CircuitBreaker} 实例并竞态 {@code ++consecutiveFailures}，丢失更新致
 * 熔断该开不开。本测以 barrier-aligned 高并发 recordFailure 压测：
 *
 * <p>{@code N} 线程各执行 {@code K} 次 recordFailure（threshold = N×K），全部完成后断言 OPEN。
 * 无同步时丢失更新 → 累计 < threshold → CLOSED（测试失败）；加 synchronized 后精确达阈值 → OPEN。
 */
class CircuitBreakerConcurrencyTest {

    @Test
    void concurrentRecordFailures_noLostIncrements_opensAtExactThreshold() throws Exception {
        int threads = 64;
        int perThread = 100;
        int threshold = threads * perThread; // 恰好达阈值即应 OPEN
        CircuitBreaker breaker = new CircuitBreaker(threshold, 60_000L, () -> 0L);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    breaker.recordFailure();
                }
                done.countDown();
            });
        }
        ready.await();
        start.countDown(); // 所有线程同时开始密集自增
        done.await();
        pool.shutdown();

        // 同步正确：6400 次自增无丢失 → 达阈值 → OPEN；无同步丢失更新 → 不足阈值 → CLOSED
        assertThat(breaker.state())
                .as("并发 recordFailure 不丢失更新，达阈值即应 OPEN")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }
}
