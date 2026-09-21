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
 * 会话缓存服务测试（Phase 3·第二层会话缓存基础；Phase 22 T98 扩展 loadMemory/save）。
 *
 * <p>{@link SessionCacheService} 收口会话记忆存取：load/append + TTL，底层依赖 {@link SessionCacheStore}
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
    void loadMemory_returnsSummaryAndMessages() {
        // T98：全量记忆视图（摘要 + 消息）；load 保持存量口径 = memory.messages()
        FakeStore store = new FakeStore();
        store.put("s1", new SessionMemory("主题", List.of(new ChatMessage.User("问"))));
        SessionCacheService svc = new SessionCacheService(store, TTL);

        SessionMemory memory = svc.loadMemory("s1");

        assertThat(memory.summary()).isEqualTo("主题");
        assertThat(memory.messages()).hasSize(1);
        assertThat(svc.load("s1")).hasSize(1);
    }

    @Test
    void load_unknownSession_returnsEmpty() {
        SessionCacheService svc = new SessionCacheService(new FakeStore(), TTL);

        assertThat(svc.load("nope")).isEmpty();
        assertThat(svc.loadMemory("nope").messages()).isEmpty();
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
    void save_delegatesWholeMemoryToStore() {
        // T100 压缩写回路径
        FakeStore store = new FakeStore();
        SessionCacheService svc = new SessionCacheService(store, TTL);
        SessionMemory memory = new SessionMemory("新摘要", List.of(new ChatMessage.User("尾")));

        svc.save("s1", memory);

        assertThat(store.memory("s1")).isSameAs(memory);
        assertThat(store.lastTtl("s1")).isEqualTo(TTL);
    }

    @Test
    void load_storeThrows_wrapsInSessionCacheException() {
        SessionCacheService svc = new SessionCacheService(new ThrowingStore(), TTL);

        assertThatThrownBy(() -> svc.load("s1"))
                .isInstanceOf(SessionCacheException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("会话缓存");
        assertThatThrownBy(() -> svc.loadMemory("s1"))
                .isInstanceOf(SessionCacheException.class);
    }

    @Test
    void append_storeThrows_wrapsInSessionCacheException() {
        SessionCacheService svc = new SessionCacheService(new ThrowingStore(), TTL);

        assertThatThrownBy(() -> svc.append("s1", List.of(new ChatMessage.User("x"))))
                .isInstanceOf(SessionCacheException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void save_storeThrows_wrapsInSessionCacheException() {
        SessionCacheService svc = new SessionCacheService(new ThrowingStore(), TTL);

        assertThatThrownBy(() -> svc.save("s1", SessionMemory.empty()))
                .isInstanceOf(SessionCacheException.class);
    }

    @Test
    void mergeSave_appliesMergerToLatest_andSavesWithDefaultTtl() {
        // 2026-09-21 review 修订：压缩写回收口为 mergeSave（锁内 load→merge→save）
        FakeStore store = new FakeStore();
        store.put("s1", new SessionMemory("旧摘要", List.of(new ChatMessage.User("第一轮"))));
        SessionCacheService svc = new SessionCacheService(store, TTL);

        SessionMemory saved = svc.mergeSave("s1", mem ->
                new SessionMemory("新摘要", mem.messages()));

        assertThat(saved.summary()).isEqualTo("新摘要");
        assertThat(store.memory("s1").summary()).isEqualTo("新摘要");
        assertThat(store.memory("s1").messages()).hasSize(1);
        assertThat(store.lastTtl("s1")).isEqualTo(TTL);
    }

    @Test
    void mergeSave_andAppend_mutuallyExclusivePerSession() throws Exception {
        // 互斥钉死：mergeSave 持锁期间同会话 append 必须等待——append 的 get/set 不可能
        // 横跨压缩写回用旧值覆盖（Redis 读-改-写竞态根治，2026-09-21）
        FakeStore store = new FakeStore();
        store.put("s1", SessionMemory.ofMessages(List.of(new ChatMessage.User("旧"))));
        SessionCacheService svc = new SessionCacheService(store, TTL);
        java.util.concurrent.CountDownLatch mergerEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean appendedDuringMerge = new java.util.concurrent.atomic.AtomicBoolean(false);

        Thread merger = new Thread(() -> svc.mergeSave("s1", mem -> {
            mergerEntered.countDown();
            try {
                release.await(); // 持锁挂起，给 append 撞锁的机会
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new SessionMemory("摘要", mem.messages());
        }));
        merger.start();
        assertThat(mergerEntered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        Thread appender = new Thread(() -> {
            svc.append("s1", List.of(new ChatMessage.User("新")));
            appendedDuringMerge.set(true);
        });
        appender.start();
        Thread.sleep(80); // 互斥成立则 append 全程阻塞；若被破坏，append 微秒级完成

        assertThat(appendedDuringMerge.get()).isFalse(); // append 被压缩写回阻塞

        release.countDown();
        merger.join(2000);
        appender.join(2000);

        assertThat(appendedDuringMerge.get()).isTrue();
        assertThat(store.memory("s1").summary()).isEqualTo("摘要");
        assertThat(store.memory("s1").messages()).hasSize(2); // append 的消息在写回后保留
    }

    // ---- test fakes ----

    /** 内存 fake 存储：记录写入的记忆与最近一次 TTL，供断言。 */
    static final class FakeStore implements SessionCacheStore {
        private final Map<String, SessionMemory> data = new ConcurrentHashMap<>();
        private final Map<String, Duration> ttls = new ConcurrentHashMap<>();

        void put(String sessionId, List<ChatMessage> messages) {
            data.put(sessionId, SessionMemory.ofMessages(messages));
        }

        void put(String sessionId, SessionMemory memory) {
            data.put(sessionId, memory);
        }

        List<ChatMessage> messages(String sessionId) {
            return memory(sessionId).messages();
        }

        SessionMemory memory(String sessionId) {
            return data.getOrDefault(sessionId, SessionMemory.empty());
        }

        Duration lastTtl(String sessionId) {
            return ttls.get(sessionId);
        }

        @Override
        public SessionMemory load(String sessionId) {
            return memory(sessionId);
        }

        @Override
        public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
            data.compute(sessionId, (k, existing) -> {
                List<ChatMessage> merged = (existing != null) ? existing.mutableMessages() : new ArrayList<>();
                merged.addAll(messages);
                return new SessionMemory((existing != null) ? existing.summary() : null, merged);
            });
            ttls.put(sessionId, ttl);
        }

        @Override
        public void save(String sessionId, SessionMemory memory, Duration ttl) {
            data.put(sessionId, memory);
            ttls.put(sessionId, ttl);
        }
    }

    /** 永远抛异常的存储：模拟 Redis 故障。 */
    static final class ThrowingStore implements SessionCacheStore {
        @Override
        public SessionMemory load(String sessionId) {
            throw new RuntimeException("redis connection refused");
        }

        @Override
        public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
            throw new RuntimeException("redis connection refused");
        }

        @Override
        public void save(String sessionId, SessionMemory memory, Duration ttl) {
            throw new RuntimeException("redis connection refused");
        }
    }
}
