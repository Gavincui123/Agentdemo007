package com.agentdemo007.session.cache;

import com.agentdemo007.common.exception.SessionCacheException;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 会话缓存存储测试（Phase 3·真实 Redis 后端，用 Mockito 替代连接）。
 *
 * <p>验证：编解码往返、空键、Redis 故障包装为 {@link SessionCacheException}（由
 * {@code SessionLoadStep} 捕获后走 {@code SESSION_DOWN} 话术短路）。
 */
@ExtendWith(MockitoExtension.class)
class RedisSessionCacheStoreTest {

    private final ChatMessageCodec codec = new ChatMessageCodec(new ObjectMapper());

    @Mock private ValueOperations<String, String> ops;
    @Mock private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void setup() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
    }

    @Test
    void load_decodesStoredHistory() {
        when(ops.get("session:s1")).thenReturn(codec.encode(List.of(
                new ChatMessage.User("你好"), new ChatMessage.Ai("好"))));

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);
        List<ChatMessage> history = store.load("s1");

        assertThat(history).hasSize(2);
        assertThat(history.get(1)).isInstanceOf(ChatMessage.Ai.class);
        assertThat(history.get(1).content()).isEqualTo("好");
        verify(ops).get("session:s1");
    }

    @Test
    void load_emptyKey_returnsEmpty() {
        when(ops.get("session:s1")).thenReturn(null);

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);

        assertThat(store.load("s1")).isEmpty();
    }

    @Test
    void append_newSession_encodesAndSetsTtl() {
        when(ops.get("session:s1")).thenReturn(null);

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);
        store.append("s1", List.of(new ChatMessage.User("hi")), Duration.ofSeconds(60));

        verify(ops).set(eq("session:s1"), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    void append_concatenatesToExistingHistory() {
        when(ops.get("session:s1")).thenReturn(codec.encode(List.of(new ChatMessage.User("一"))));

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);
        store.append("s1", List.of(new ChatMessage.Ai("二")), Duration.ofSeconds(60));

        verify(ops).set(eq("session:s1"),
                eq(codec.encode(List.of(new ChatMessage.User("一"), new ChatMessage.Ai("二")))),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    void load_redisFailure_throwsSessionCacheException() {
        when(ops.get("session:s1")).thenThrow(new RuntimeException("connection refused"));

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);

        assertThatThrownBy(() -> store.load("s1"))
                .isInstanceOf(SessionCacheException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
