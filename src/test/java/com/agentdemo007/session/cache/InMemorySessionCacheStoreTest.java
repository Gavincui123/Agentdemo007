package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存会话缓存存储测试（Phase 3·无 Redis 时的兜底存储）。
 *
 * <p>dev 环境默认启用，保证应用不依赖 Redis 也能启动与多轮对话（会话不持久化）。
 */
class InMemorySessionCacheStoreTest {

    private final InMemorySessionCacheStore store =
            new InMemorySessionCacheStore(new ChatMessageCodec(new ObjectMapper()), Duration.ofSeconds(60));

    @BeforeEach
    void clear() {
        store.clear();
    }

    @Test
    void load_newSession_returnsEmpty() {
        assertThat(store.load("new")).isEmpty();
    }

    @Test
    void append_thenLoad_roundTrips() {
        store.append("s1", List.of(new ChatMessage.User("你好"), new ChatMessage.Ai("好")), Duration.ofSeconds(60));

        List<ChatMessage> history = store.load("s1");

        assertThat(history).hasSize(2);
        assertThat(history.get(0)).isInstanceOf(ChatMessage.User.class);
        assertThat(history.get(0).content()).isEqualTo("你好");
        assertThat(history.get(1)).isInstanceOf(ChatMessage.Ai.class);
    }

    @Test
    void append_appendsToExistingHistory() {
        store.append("s1", List.of(new ChatMessage.User("一")), Duration.ofSeconds(60));
        store.append("s1", List.of(new ChatMessage.Ai("二")), Duration.ofSeconds(60));

        assertThat(store.load("s1")).hasSize(2);
    }
}
