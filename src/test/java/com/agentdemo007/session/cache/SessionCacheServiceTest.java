package com.agentdemo007.session.cache;

import com.agentdemo007.common.exception.SessionCacheException;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话缓存服务测试（Phase 3·第二层会话缓存基础）。
 *
 * <p>{@link SessionCacheService} 收口会话历史存取：load/append + TTL，底层依赖 {@link SessionCacheStore}
 * 可插拔（生产 Redis 实现，测试用内存 fake）。任何存储异常统一封装为 {@link SessionCacheException}，
 * 供流水线会话步骤捕获后走 {@code SESSION_DOWN} 话术短路（§5.12 + eval/degradation.json deg-003）。
 */
class SessionCacheServiceTest {

    private static final Duration TTL = Duration.ofSeconds(3600);

    @Test
    void load_returnsStoreHistory() {
        FakeStore store = new FakeStore();
        store.put("s1", List.of(new ChatMessage.User("你好"), new ChatMessage.Ai("您好")));
        SessionCacheService svc = new SessionCacheService(store, TTL);

        List<ChatMessage> history = svc.load("s1");

        assertThat(history).hasSize(2);
        assertThat(history.get(0)).isInstanceOf(ChatMessage.User.class);
    }

    @Test
    void load_unknownSession_returnsEmpty() {
        SessionCacheService svc = new SessionCacheService(new FakeStore(), TTL);

        assertThat(svc.load("nope")).isEmpty();
    }

    @Test
    void append_defaultTtl_delegatesToStore() {
        FakeStore store = new FakeStore();
        SessionCacheService svc = new SessionCacheService(store, TTL);

        svc.append("s1", List.of(new ChatMessage.User("你好"), new ChatMessage.Ai("您好")));

        assertThat(store.messages("s1")).hasSize(2);
        assertThat(store.lastTtl("s1")).isEqualTo(TTL); // 默认 TTL 透传
    }

    @Test
    void append_explicitTtl_overridesDefault() {
        FakeStore store = new FakeStore();
        SessionCacheService svc = new SessionCacheService(store, TTL);
        Duration custom = Duration.ofSeconds(60);

        svc.append("s1", List.of(new ChatMessage.User("x")), custom);

        assertThat(store.lastTtl("s1")).isEqualTo(custom);
    }

    @Test
    void append_appendsToExistingHistory() {
        FakeStore store = new FakeStore();
        store.put("s1", new ArrayList<>(List.of(new ChatMessage.User("第一轮"))));
        SessionCacheService svc = new SessionCacheService(store, TTL);

        svc.append("s1", List.of(new ChatMessage.Ai("回复")));

        assertThat(store.messages("s1")).hasSize(2);
        assertThat(store.messages("s1").get(1)).isInstanceOf(ChatMessage.Ai.class);
    }

    @Test
    void load_storeThrows_wrapsInSessionCacheException() {
        SessionCacheService svc = new SessionCacheService(new ThrowingStore(), TTL);

        assertThatThrownBy(() -> svc.load("s1"))
                .isInstanceOf(SessionCacheException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("会话缓存");
    }

    @Test
    void append_storeThrows_wrapsInSessionCacheException() {
        SessionCacheService svc = new SessionCacheService(new ThrowingStore(), TTL);

        assertThatThrownBy(() -> svc.append("s1", List.of(new ChatMessage.User("x"))))
                .isInstanceOf(SessionCacheException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    // ---- test fakes ----

    /** 内存 fake 存储：记录写入的消息与最近一次 TTL，供断言。 */
    static final class FakeStore implements SessionCacheStore {
        private final Map<String, List<ChatMessage>> data = new ConcurrentHashMap<>();
        private final Map<String, Duration> ttls = new ConcurrentHashMap<>();

        void put(String sessionId, List<ChatMessage> messages) {
            data.put(sessionId, messages);
        }

        List<ChatMessage> messages(String sessionId) {
            return data.getOrDefault(sessionId, List.of());
        }

        Duration lastTtl(String sessionId) {
            return ttls.get(sessionId);
        }

        @Override
        public List<ChatMessage> load(String sessionId) {
            return new ArrayList<>(data.getOrDefault(sessionId, List.of()));
        }

        @Override
        public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
            data.computeIfAbsent(sessionId, k -> new ArrayList<>()).addAll(messages);
            ttls.put(sessionId, ttl);
        }
    }

    /** 永远抛异常的存储：模拟 Redis 故障。 */
    static final class ThrowingStore implements SessionCacheStore {
        @Override
        public List<ChatMessage> load(String sessionId) {
            throw new RuntimeException("redis connection refused");
        }

        @Override
        public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
            throw new RuntimeException("redis connection refused");
        }
    }
}
