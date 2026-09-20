package com.agentdemo007.gate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存配额存储（闸口原有计数逻辑迁入）：常驻 O(活 IP 数)，超阈值剪枝过期日期键。
 * 重启清零可接受（dev / Redis 未启用场景）；发布口径用 {@link RedisGateQuotaStore}。
 */
public class InMemoryGateQuotaStore implements GateQuotaStore {

    private static final int MAX_COUNTER_KEYS = 4096;

    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public InMemoryGateQuotaStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public int incrementAndGet(String key) {
        pruneIfNeeded();
        return counters.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public int peek(String key) {
        AtomicInteger c = counters.get(key);
        return c == null ? 0 : c.get();
    }

    private void pruneIfNeeded() {
        if (counters.size() <= MAX_COUNTER_KEYS) {
            return;
        }
        String today = LocalDate.now(clock).toString();
        counters.keySet().removeIf(k -> !k.endsWith("|" + today));
    }
}
