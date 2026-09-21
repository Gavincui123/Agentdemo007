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
 *
 * <p>Phase 22：值结构升级为 {@link SessionMemory}（{@code append} 保留既有摘要，
 * {@code save} 整体替换——压缩写回路径）。
 */
public class InMemorySessionCacheStore implements SessionCacheStore {

    private final ConcurrentMap<String, SessionMemory> data = new ConcurrentHashMap<>();

    public InMemorySessionCacheStore(ChatMessageCodec codec, Duration defaultTtl) {
        // codec 仅 Redis 后端需要序列化；内存存储直接持有对象，这里保留参数以统一构造形态。
    }

    @Override
    public SessionMemory load(String sessionId) {
        SessionMemory memory = data.get(sessionId);
        return (memory != null) ? memory : SessionMemory.empty();
    }

    @Override
    public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
        data.compute(sessionId, (k, existing) -> {
            List<ChatMessage> merged = (existing != null) ? existing.mutableMessages() : new ArrayList<>();
            merged.addAll(messages);
            String summary = (existing != null) ? existing.summary() : null;
            return new SessionMemory(summary, merged);
        });
    }

    @Override
    public void save(String sessionId, SessionMemory memory, Duration ttl) {
        data.put(sessionId, memory);
    }

    /** 测试清理用。 */
    void clear() {
        data.clear();
    }
}
