package com.agentdemo007.session.summary;

import com.agentdemo007.session.cache.SessionCacheService;
import com.agentdemo007.session.cache.SessionCacheStore;
import com.agentdemo007.session.cache.SessionMemory;
import com.agentdemo007.session.cache.SessionWindower;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆压缩服务测试（Phase 22·T100 终局异步压缩 + T101 摘要契约）。
 *
 * <p>钉死：触发阈值（真实 usage ≥ 80% 预算）、增量合并（旧摘要 + 滑出轮次，不重压全量）、
 * 业务键白名单（滑出文本中的单号压缩后必须仍在，运行时补齐）、摘要垃圾降级（沿用旧摘要）、
 * 竞态合并（压缩期间新追加消息不丢）。测试注入直接执行器（Runnable::run）——
 * 触发路径与压缩主体在同一线程确定性执行；生产构造为专用守护单线程（与主链路零共享）。
 */
class SessionCompactionServiceTest {

    private final FakeStore store = new FakeStore();
    private final SessionCacheService cache = new SessionCacheService(store, Duration.ofSeconds(60));
    private final SessionWindower windower = new SessionWindower(10, 1.0); // 预算 10 字符，强制滑出

    /** 可编程摘要 Hook：返回预设摘要，或 empty 模拟 LLM 失败/垃圾；捕获入参供断言。 */
    private static final class ScriptedHook implements SummaryHook {
        private String scripted = "合并后的摘要";
        private boolean fail = false;
        private String rollingOldSummary;
        private List<ChatMessage> rollingSlidOut;
        /** 摘要回调时机执行的动作（模拟压缩期间并发 append 的竞态窗口）。 */
        private Runnable duringRolling;

        @Override
        public Optional<String> summarize(List<ChatMessage> priorHistory, String currentInput) {
            return Optional.empty();
        }

        @Override
        public Optional<String> summarizeRolling(String oldSummary, List<ChatMessage> slidOut) {
            this.rollingOldSummary = oldSummary;
            this.rollingSlidOut = slidOut;
            if (duringRolling != null) {
                duringRolling.run();
            }
            return fail ? Optional.empty() : Optional.of(scripted);
        }
    }

    private final ScriptedHook hook = new ScriptedHook();

    private SessionCompactionService service(int summaryMaxChars) {
        return new SessionCompactionService(cache, hook, windower, true, 1200, 0.8, summaryMaxChars,
                Runnable::run);
    }

    @Test
    void maybeCompact_belowThreshold_doesNothing() {
        SessionCompactionService svc = service(200);
        store.put("s1", SessionMemory.ofMessages(threeTurns()));

        svc.maybeCompactAsync("s1", 959); // 阈值 = 1200×0.8 = 960

        assertThat(store.memory("s1").messages()).hasSize(6);
        assertThat(store.memory("s1").hasSummary()).isFalse();
    }

    @Test
    void maybeCompact_atThreshold_compacts() {
        SessionCompactionService svc = service(200);
        store.put("s1", SessionMemory.ofMessages(threeTurns()));

        svc.maybeCompactAsync("s1", 960); // 恰在阈值

        SessionMemory memory = store.memory("s1");
        assertThat(memory.hasSummary()).isTrue();
        assertThat(memory.messages()).hasSize(2); // 窗口只剩最后一轮
    }

    @Test
    void maybeCompact_nullUsageOrSession_skips() {
        SessionCompactionService svc = service(200);
        store.put("s1", SessionMemory.ofMessages(threeTurns()));

        svc.maybeCompactAsync("s1", null); // usage 缺失（话术短路/取消轮）
        svc.maybeCompactAsync(null, 2000); // 无会话

        assertThat(store.memory("s1").messages()).hasSize(6);
    }

    @Test
    void maybeCompact_disabled_skips() {
        SessionCompactionService svc = new SessionCompactionService(
                cache, hook, windower, false, 1200, 0.8, 200, Runnable::run);
        store.put("s1", SessionMemory.ofMessages(threeTurns()));

        svc.maybeCompactAsync("s1", 5000);

        assertThat(store.memory("s1").messages()).hasSize(6);
    }

    @Test
    void compact_incrementalMerge_usesOldSummaryPlusSlidOut() {
        // 增量合并：summarizeRolling(旧摘要, 滑出轮次)，永不重压全量
        SessionCompactionService svc = service(200);
        hook.scripted = "主题延续：退款进度待跟进";
        store.put("s1", new SessionMemory("旧摘要：咨询退款", threeTurns()));

        svc.compact("s1");

        assertThat(hook.rollingOldSummary).isEqualTo("旧摘要：咨询退款");
        assertThat(hook.rollingSlidOut).hasSize(4); // 前两轮滑出
        assertThat(store.memory("s1").summary()).isEqualTo("主题延续：退款进度待跟进");
        assertThat(store.memory("s1").messages()).hasSize(2);
    }

    @Test
    void compact_businessKeysInSlidOut_mustSurviveInSummary() {
        // T101 白名单断言（运行时补齐钉）：滑出文本中的订单号，压缩后必须仍在摘要里
        SessionCompactionService svc = service(200);
        hook.scripted = "用户询问过订单进度（未提及单号）"; // 模拟 LLM 摘要漏单号
        store.put("s1", new SessionMemory(null, List.of(
                new ChatMessage.User("我的订单 ORD-001 到哪了"),
                new ChatMessage.Ai("ORD-001 已发货"),
                new ChatMessage.User("短问"), new ChatMessage.Ai("短答"))));

        svc.compact("s1");

        String summary = store.memory("s1").summary();
        assertThat(summary).contains("ORD-001");
        assertThat(summary).contains("涉及单号");
    }

    @Test
    void compact_llmFails_fallsBackToOldSummary_keysStillPinned() {
        // 宁缺毋滥：LLM 失败 → 沿用旧摘要；业务键照常补齐（correctness 双保险，不依赖摘要质量）
        SessionCompactionService svc = service(200);
        hook.fail = true;
        store.put("s1", new SessionMemory("旧摘要", List.of(
                new ChatMessage.User("查 ORD-002"),
                new ChatMessage.Ai("已退款"),
                new ChatMessage.User("短问"), new ChatMessage.Ai("短答"))));

        svc.compact("s1");

        String summary = store.memory("s1").summary();
        assertThat(summary).contains("旧摘要");
        assertThat(summary).contains("ORD-002");
    }

    @Test
    void compact_noOldSummary_llmFails_noKeys_summaryDropped() {
        // 无旧摘要 + LLM 失败 + 无业务键 → 本周期无摘要（读侧多喂原文，L0 兜底）
        SessionCompactionService svc = service(200);
        hook.fail = true;
        store.put("s1", SessionMemory.ofMessages(List.of(
                new ChatMessage.User("闲聊一轮很长的问题一二三四五"),
                new ChatMessage.Ai("闲聊回答一二三四五"),
                new ChatMessage.User("短问"), new ChatMessage.Ai("短答"))));

        svc.compact("s1");

        assertThat(store.memory("s1").hasSummary()).isFalse();
        assertThat(store.memory("s1").messages()).hasSize(2); // 消息仍按窗口裁剪
    }

    @Test
    void compact_summaryClampedToMaxChars_keysNotLostByTruncation() {
        // ≤200 契约：超长摘要截断，业务键行保留（截断不丢键）
        SessionCompactionService svc = service(60);
        hook.scripted = "很长".repeat(60); // 240 字
        store.put("s1", new SessionMemory(null, List.of(
                new ChatMessage.User("查 ORD-003 很长很长很长很长很长"),
                new ChatMessage.Ai("好的"),
                new ChatMessage.User("短问"), new ChatMessage.Ai("短答"))));

        svc.compact("s1");

        String summary = store.memory("s1").summary();
        assertThat(summary.length()).isLessThanOrEqualTo(60);
        assertThat(summary).endsWith("ORD-003");
    }

    @Test
    void compact_messagesAppendedDuringCompaction_arePreserved() {
        // 竞态合并：压缩读值后、写回前的并发 append——写回时拼回窗口尾，不丢历史
        SessionCompactionService svc = service(200);
        store.put("s1", SessionMemory.ofMessages(threeTurns()));
        hook.duringRolling = () -> store.append("s1",
                List.of(new ChatMessage.User("压缩期间新到的问题"), new ChatMessage.Ai("压缩期间新回复")),
                Duration.ofSeconds(60));

        svc.compact("s1");

        List<ChatMessage> kept = store.memory("s1").messages();
        assertThat(kept).extracting(ChatMessage::content)
                .contains("压缩期间新到的问题", "压缩期间新回复");
    }

    @Test
    void compact_windowNotYetFull_noOp() {
        SessionCompactionService svc = service(200);
        store.put("s1", SessionMemory.ofMessages(List.of(new ChatMessage.User("短"), new ChatMessage.Ai("答"))));

        svc.compact("s1"); // 窗口装得下 → 无滑出

        assertThat(store.memory("s1").messages()).hasSize(2);
        assertThat(store.memory("s1").hasSummary()).isFalse();
    }

    @Test
    void compact_emptySession_noOp() {
        service(200).compact("empty-session");
        assertThat(store.memory("empty-session").messages()).isEmpty();
    }

    // ---- helpers / fakes ----

    private static List<ChatMessage> threeTurns() {
        return List.of(
                new ChatMessage.User("第一轮问题一二三四五"),
                new ChatMessage.Ai("第一轮回答一二三四五"),
                new ChatMessage.User("第二轮问题一二三四五"),
                new ChatMessage.Ai("第二轮回答一二三四五"),
                new ChatMessage.User("第三轮短问"),
                new ChatMessage.Ai("第三轮短答"));
    }

    /** 内存 fake 存储：记录写入的记忆值，append 保留摘要（同 Redis 语义）。 */
    static final class FakeStore implements SessionCacheStore {
        final Map<String, SessionMemory> data = new ConcurrentHashMap<>();

        void put(String sessionId, SessionMemory memory) {
            data.put(sessionId, memory);
        }

        SessionMemory memory(String sessionId) {
            return data.getOrDefault(sessionId, SessionMemory.empty());
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
        }

        @Override
        public void save(String sessionId, SessionMemory memory, Duration ttl) {
            data.put(sessionId, memory);
        }
    }
}
