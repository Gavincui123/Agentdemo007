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
 * Redis 会话缓存存储测试（Phase 3·真实 Redis 后端，用 Mockito 替代连接；Phase 22 T98 值结构升级回归）。
 *
 * <p>验证：编解码往返（新 object 格式 + 存量数组格式兼容）、空键、摘要保留、
 * Redis 故障包装为 {@link SessionCacheException}（由 {@code SessionLoadStep} 捕获后走
 * {@code SESSION_DOWN} 话术短路）。
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
        List<ChatMessage> history = store.load("s1").messages();

        assertThat(history).hasSize(2);
        assertThat(history.get(1)).isInstanceOf(ChatMessage.Ai.class);
        assertThat(history.get(1).content()).isEqualTo("好");
        verify(ops).get("session:s1");
    }

    @Test
    void load_legacyArrayFormat_decodesAsNoSummary() {
        // T98 存量兼容：升级前写入的纯消息数组（无 summary 字段）→ 视为无摘要，零迁移
        String legacy = codec.encode(List.of(new ChatMessage.User("旧值问"), new ChatMessage.Ai("旧值答")));
        when(ops.get("session:s1")).thenReturn(legacy);

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);
        SessionMemory memory = store.load("s1");

        assertThat(memory.hasSummary()).isFalse();
        assertThat(memory.messages()).hasSize(2);
        assertThat(memory.messages().get(0).content()).isEqualTo("旧值问");
    }

    @Test
    void load_emptyKey_returnsEmpty() {
        when(ops.get("session:s1")).thenReturn(null);

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);

        assertThat(store.load("s1").messages()).isEmpty();
    }

    @Test
    void append_newSession_encodesAndSetsTtl() {
        when(ops.get("session:s1")).thenReturn(null);

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);
        store.append("s1", List.of(new ChatMessage.User("hi")), Duration.ofSeconds(60));

        verify(ops).set(eq("session:s1"), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    void append_concatenatesToExistingHistory_andPreservesSummary() {
        // T100 配套：append 读写改写全值——既有滚动摘要必须原样保留
        when(ops.get("session:s1")).thenReturn(
                codec.encodeMemory(new SessionMemory("既有摘要", List.of(new ChatMessage.User("一")))));

        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);
        store.append("s1", List.of(new ChatMessage.Ai("二")), Duration.ofSeconds(60));

        verify(ops).set(eq("session:s1"),
                eq(codec.encodeMemory(new SessionMemory("既有摘要",
                        List.of(new ChatMessage.User("一"), new ChatMessage.Ai("二"))))),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    void save_encodesWholeMemory_withTtl() {
        // T100 压缩写回路径
        RedisSessionCacheStore store = new RedisSessionCacheStore(redisTemplate, codec);
        SessionMemory memory = new SessionMemory("新摘要", List.of(new ChatMessage.User("尾")));

        store.save("s1", memory, Duration.ofSeconds(60));

        verify(ops).set(eq("session:s1"), eq(codec.encodeMemory(memory)), eq(Duration.ofSeconds(60)));
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
