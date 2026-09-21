package com.agentdemo007.session.profile;

import java.time.Duration;
import java.util.Map;

/**
 * 用户画像存储抽象（Phase 22·T102，收口：存储位置可插拔——镜像 {@code SessionCacheStore} 范式）。
 *
 * <p>生产实现 {@code RedisUserProfileStore}（user 级哈希，TTL 90d 惰性续期）；
 * dev/测试 {@code InMemoryUserProfileStore}。任何实现不得抛出未受检异常打断主链路——
 * 调用方 {@link UserProfileService} 统一 fail-open（画像读取失败 → 无画像照常答）。
 */
public interface UserProfileStore {

    /** 加载某用户全部画像字段（key= {@link ProfileCategory#name()}；无画像/已过期 → 空表）。 */
    Map<String, String> loadAll(String userId);

    /**
     * 写入单字段（新声明覆盖旧值）并惰性续期 TTL。
     *
     * @param field   {@link ProfileCategory#name()}
     * @param content 已过守门的画像内容
     * @param ttl     过期时长（90d）
     */
    void saveField(String userId, String field, String content, Duration ttl);

    /** 遗忘权：删除该用户全部画像字段。 */
    void delete(String userId);
}
