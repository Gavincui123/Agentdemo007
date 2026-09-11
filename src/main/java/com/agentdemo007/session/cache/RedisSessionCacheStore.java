package com.agentdemo007.session.cache;

import com.agentdemo007.common.exception.SessionCacheException;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 会话缓存存储（第二层·真实 Redis 后端）。
 *
 * <p>编解码交由 {@link ChatMessageCodec}（Phase 3 序列化内核），键前缀 {@code session:}。
 * 任何 Redis 访问异常（连接拒绝、超时、序列化失败）统一包装为
 * {@link SessionCacheException}（保留 cause），由 {@code SessionCacheService.load/append}
 * 与 {@code SessionLoadStep} 捕获后走 {@code SESSION_DOWN} 话术短路，不向用户抛 5xx。
 */
public class RedisSessionCacheStore implements SessionCacheStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionCacheStore.class);
    private static final String KEY_PREFIX = "session:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ChatMessageCodec codec;

    public RedisSessionCacheStore(RedisTemplate<String, String> redisTemplate, ChatMessageCodec codec) {
        this.redisTemplate = redisTemplate;
        this.codec = codec;
    }

    @Override
    public List<ChatMessage> load(String sessionId) {
        try {
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            String json = ops.get(KEY_PREFIX + sessionId);
            return codec.decode(json); // null/空白 → 空列表
        } catch (SessionCacheException e) {
            throw e;
        } catch (Exception e) {
            log.error("Redis 会话历史加载失败：sessionId={} reason={}", sessionId, e.getMessage(), e);
            throw new SessionCacheException("Redis 会话加载失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
        try {
            String key = KEY_PREFIX + sessionId;
            ValueOperations<String, String> ops = redisTemplate.opsForValue();
            String existing = ops.get(key);
            List<ChatMessage> all = codec.decode(existing);
            all.addAll(messages);
            ops.set(key, codec.encode(all), ttl);
        } catch (SessionCacheException e) {
            throw e;
        } catch (Exception e) {
            log.error("Redis 会话历史写入失败：sessionId={} reason={}", sessionId, e.getMessage(), e);
            throw new SessionCacheException("Redis 会话写入失败：" + e.getMessage(), e);
        }
    }
}
