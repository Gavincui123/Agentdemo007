package com.agentdemo007.session.profile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存用户画像存储（dev/无 Redis 兜底，镜像 {@code InMemorySessionCacheStore} 范式）。
 *
 * <p>TTL 惰性实现：每用户记过期时刻，读取时过期即清（进程内生命周期足够演示语义；
 * Redis 后端才真正按 90d 过期 + 续期）。
 */
public class InMemoryUserProfileStore implements UserProfileStore {

    private final ConcurrentHashMap<String, Map<String, String>> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> expiry = new ConcurrentHashMap<>();

    @Override
    public Map<String, String> loadAll(String userId) {
        Map<String, String> profile = data.get(userId);
        if (profile == null) {
            return Map.of();
        }
        Instant deadline = expiry.get(userId);
        if (deadline != null && Instant.now().isAfter(deadline)) {
            data.remove(userId);
            expiry.remove(userId);
            return Map.of();
        }
        return new HashMap<>(profile);
    }

    @Override
    public void saveField(String userId, String field, String content, Duration ttl) {
        data.compute(userId, (k, existing) -> {
            Map<String, String> profile = (existing != null) ? new HashMap<>(existing) : new HashMap<>();
            profile.put(field, content);
            return profile;
        });
        expiry.put(userId, Instant.now().plus(ttl)); // 惰性续期：每次写入重置过期时刻
    }

    @Override
    public void delete(String userId) {
        data.remove(userId);
        expiry.remove(userId);
    }

    /** 测试清理用。 */
    void clear() {
        new ArrayList<>(data.keySet()).forEach(this::delete);
    }
}
