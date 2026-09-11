package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;

import java.time.Duration;
import java.util.List;

/**
 * 会话历史存储抽象（收口：存储位置可插拔）。
 *
 * <p>生产实现 {@code RedisSessionCacheStore}（Redis）；测试用内存 fake。
 * 无论底层是 Redis 还是其它，{@link SessionCacheService} 只依赖本接口，
 * 保证会话历史存取入口唯一、可替换。
 */
public interface SessionCacheStore {

    /** 加载会话历史（不存在返回空列表）。 */
    List<ChatMessage> load(String sessionId);

    /** 追加消息并刷新 TTL。 */
    void append(String sessionId, List<ChatMessage> messages, Duration ttl);
}
