package com.agentdemo007.session.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis 用户画像存储（Phase 22·T102 生产后端）。
 *
 * <p>user 级哈希 {@code profile:{userId}}：field={@link ProfileCategory#name()}，value=画像内容。
 * TTL（默认 90d，{@code app.memory.profile.ttl}）<b>惰性续期</b>——每次写入/命中读取都重置过期
 * （活跃用户画像不因周期性停用而遗忘，长期沉默自然过期）；{@code delete} 即遗忘权（整哈希删除）。
 *
 * <p>任何 Redis 异常向上抛由 {@link UserProfileService} 统一 fail-open（画像读取失败 → 无画像照常答），
 * 本类不吞异常、不打断主链路语义。
 */
public class RedisUserProfileStore implements UserProfileStore {

    private static final Logger log = LoggerFactory.getLogger(RedisUserProfileStore.class);
    private static final String KEY_PREFIX = "profile:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisUserProfileStore(StringRedisTemplate redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    @Override
    public Map<String, String> loadAll(String userId) {
        String key = KEY_PREFIX + userId;
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return Map.of();
        }
        redisTemplate.expire(key, ttl); // 惰性续期：命中读取重置 TTL
        Map<String, String> result = new HashMap<>();
        entries.forEach((k, v) -> {
            if (k != null && v != null) {
                result.put(k.toString(), v.toString());
            }
        });
        return result;
    }

    @Override
    public void saveField(String userId, String field, String content, Duration ttl) {
        String key = KEY_PREFIX + userId;
        redisTemplate.opsForHash().put(key, field, content);
        redisTemplate.expire(key, ttl);
        log.debug("用户画像字段写入：userId={} field={} len={}", userId, field, content.length());
    }

    @Override
    public void delete(String userId) {
        redisTemplate.delete(KEY_PREFIX + userId);
    }
}
