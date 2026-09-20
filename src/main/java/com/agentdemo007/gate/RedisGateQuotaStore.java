package com.agentdemo007.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis 配额存储（发布口径·2026-09-18）：INCR 原子计数 + 首次写入补 48h TTL（跨自然日自清理），
 * 应用重启配额不丢。键 {@code gate:quota:{ip|yyyy-MM-dd}}。Redis 故障向上抛（调用方 tryAcquire
 * 不吞——闸口判定宁可拒绝也不无限放行？否：闸口整体 fail-open 原则不变，异常由
 * {@code AccessGateFilter} 收口为直通并告警，避免 Redis 抖动打死对话入口）。
 */
public class RedisGateQuotaStore implements GateQuotaStore {

    private static final Logger log = LoggerFactory.getLogger(RedisGateQuotaStore.class);

    public static final String KEY_PREFIX = "gate:quota:";
    private static final Duration TTL = Duration.ofHours(48);

    private final StringRedisTemplate redis;

    public RedisGateQuotaStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public int incrementAndGet(String key) {
        String redisKey = KEY_PREFIX + key;
        Long v = redis.opsForValue().increment(redisKey);
        int value = (v == null) ? 1 : v.intValue();
        if (value == 1) {
            redis.expire(redisKey, TTL); // 仅首写设置，避免每次 INCR 都带 EXPIRE
        }
        return value;
    }

    @Override
    public int peek(String key) {
        String v = redis.opsForValue().get(KEY_PREFIX + key);
        try {
            return (v == null) ? 0 : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            log.warn("闸口配额 Redis 值异常（按 0 处理）：key={} value={}", key, v);
            return 0;
        }
    }
}
