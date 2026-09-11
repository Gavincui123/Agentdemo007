package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存会话缓存存储（无 Redis 时的兜底存储，dev 环境默认）。
 *
 * <p>不依赖外部 Redis，保证应用离线也能启动与多轮对话（会话不持久化，进程重启即丢失）。
 * TTL 在内存存储中不做强制清理（进程内生命周期已隐含"过期"），Redis 后端才真正生效。
 */
public class InMemorySessionCacheStore implements SessionCacheStore {

    private final ConcurrentMap<String, List<ChatMessage>> data = new ConcurrentHashMap<>();

    public InMemorySessionCacheStore(ChatMessageCodec codec, Duration defaultTtl) {
        // codec 仅 Redis 后端需要序列化；内存存储直接持有对象，这里保留参数以统一构造形态。
    }

    @Override
    public List<ChatMessage> load(String sessionId) {
        List<ChatMessage> messages = data.get(sessionId);
        return (messages != null) ? new ArrayList<>(messages) : new ArrayList<>();
    }

    @Override
    public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
        data.computeIfAbsent(sessionId, k -> new ArrayList<>()).addAll(messages);
    }

    /** 测试清理用。 */
    void clear() {
        data.clear();
    }
}
