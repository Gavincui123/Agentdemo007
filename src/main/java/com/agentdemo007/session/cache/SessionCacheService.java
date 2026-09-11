package com.agentdemo007.session.cache;

import com.agentdemo007.common.exception.SessionCacheException;
import com.agentdemo007.session.model.ChatMessage;

import java.time.Duration;
import java.util.List;

/**
 * 会话缓存服务（第二层·会话缓存基础）。
 *
 * <p>收口会话历史存取：load/append + TTL，底层依赖可插拔的 {@link SessionCacheStore}。
 * 任何存储异常统一封装为 {@link SessionCacheException}（保留 cause），供流水线会话步骤
 * 捕获后走 {@code SESSION_DOWN} 话术短路（§5.12 + eval/degradation.json deg-003），
 * 不向用户抛 5xx。
 */
public class SessionCacheService {

    private final SessionCacheStore store;
    private final Duration defaultTtl;

    public SessionCacheService(SessionCacheStore store, Duration defaultTtl) {
        this.store = store;
        this.defaultTtl = defaultTtl;
    }

    /** 加载会话历史；存储异常 → {@link SessionCacheException}。 */
    public List<ChatMessage> load(String sessionId) {
        try {
            return store.load(sessionId);
        } catch (Exception e) {
            throw new SessionCacheException("会话缓存加载失败：" + e.getMessage(), e);
        }
    }

    /** 追加消息，使用默认 TTL。 */
    public void append(String sessionId, List<ChatMessage> messages) {
        append(sessionId, messages, defaultTtl);
    }

    /** 追加消息并显式指定 TTL。 */
    public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
        try {
            store.append(sessionId, messages, ttl);
        } catch (Exception e) {
            throw new SessionCacheException("会话缓存写入失败：" + e.getMessage(), e);
        }
    }
}
