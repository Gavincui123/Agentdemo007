package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存会话缓存存储测试（Phase 3·无 Redis 时的兜底存储；Phase 22 T98 值结构升级回归）。
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
        assertThat(store.load("new").messages()).isEmpty();
    }

    @Test
    void append_thenLoad_roundTrips() {
        store.append("s1", List.of(new ChatMessage.User("你好"), new ChatMessage.Ai("好")), Duration.ofSeconds(60));

        List<ChatMessage> history = store.load("s1").messages();

        assertThat(history).hasSize(2);
        assertThat(history.get(0)).isInstanceOf(ChatMessage.User.class);
        assertThat(history.get(0).content()).isEqualTo("你好");
        assertThat(history.get(1)).isInstanceOf(ChatMessage.Ai.class);
    }

    @Test
    void append_appendsToExistingHistory() {
        store.append("s1", List.of(new ChatMessage.User("一")), Duration.ofSeconds(60));
        store.append("s1", List.of(new ChatMessage.Ai("二")), Duration.ofSeconds(60));

        assertThat(store.load("s1").messages()).hasSize(2);
    }

    @Test
    void append_preservesExistingSummary() {
        // T98：压缩写回摘要后，下一轮 append 不得丢摘要
        store.save("s1", new SessionMemory("此前主题", List.of(new ChatMessage.User("一"))), Duration.ofSeconds(60));

        store.append("s1", List.of(new ChatMessage.Ai("二")), Duration.ofSeconds(60));

        SessionMemory memory = store.load("s1");
        assertThat(memory.summary()).isEqualTo("此前主题");
        assertThat(memory.messages()).hasSize(2);
    }

    @Test
    void save_replacesWholeMemory() {
        // T100 压缩写回路径：save 整体替换（摘要 + 裁剪后窗口）
        store.append("s1", List.of(
                new ChatMessage.User("旧一"), new ChatMessage.Ai("旧答一"),
                new ChatMessage.User("旧二"), new ChatMessage.Ai("旧答二")), Duration.ofSeconds(60));

        store.save("s1", new SessionMemory("合并摘要", List.of(new ChatMessage.User("新尾"), new ChatMessage.Ai("新答"))),
                Duration.ofSeconds(60));

        SessionMemory memory = store.load("s1");
        assertThat(memory.summary()).isEqualTo("合并摘要");
        assertThat(memory.messages()).hasSize(2);
        assertThat(memory.messages().get(0).content()).isEqualTo("新尾");
    }
}
