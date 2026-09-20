package com.agentdemo007.session;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.cache.SessionCacheService;
import com.agentdemo007.session.cache.SessionCacheStore;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话加载步骤测试（Phase 3·首个真实 PipelineStep）。
 *
 * <p>{@link SessionLoadStep} 经 {@link PipelineContext} 读写、返回 {@link StepOutcome}（落地收口契约）：
 * 正常 → 拉取 Redis 历史回填 {@code context.history} + {@code Proceed}；
 * Redis 故障（{@code SessionCacheException}）→ <b>{@code Degrade(SESSION_DOWN)} 降级单轮模式继续</b>
 * （2026-09-17 定案：缓存不杀轮，history 置空、标记 degraded；eval deg-003 已同步）。
 */
class SessionLoadStepTest {

    @Test
    void process_loadsHistoryIntoContext_andProceeds() {
        SessionCacheService svc = serviceWith(new ChatMessage.User("历史问"), new ChatMessage.Ai("历史答"));
        SessionLoadStep step = new SessionLoadStep(svc);
        PipelineContext ctx = new PipelineContext("s1", "本轮问题");

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.history()).hasSize(2);
        assertThat(ctx.history().get(1).content()).isEqualTo("历史答");
    }

    @Test
    void process_emptyHistory_proceeds() {
        SessionCacheService svc = serviceWith();
        SessionLoadStep step = new SessionLoadStep(svc);

        StepOutcome outcome = step.process(new PipelineContext("new-session", "你好"));

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
    }

    @Test
    void process_redisDown_degradesSingleTurnMode_doesNotKillTurn() {
        // 2026-09-17 定案回归钉：缓存挂 → Degrade 继续（历史置空），整轮不被杀
        SessionCacheService svc = new SessionCacheService(new ThrowingStore(), Duration.ofSeconds(60));
        SessionLoadStep step = new SessionLoadStep(svc);
        PipelineContext ctx = new PipelineContext("s1", "x");

        StepOutcome outcome = step.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Degrade.class);
        assertThat(((StepOutcome.Degrade) outcome).scenario()).isEqualTo(DegradationScenario.SESSION_DOWN);
        assertThat(ctx.history()).isEmpty(); // 故障时历史置空（单轮模式）
    }

    @Test
    void name_isSessionLoadStep() {
        SessionLoadStep step = new SessionLoadStep(serviceWith());
        assertThat(step.name()).isEqualTo("SessionLoadStep");
    }

    // ---- helpers / fakes ----

    private static SessionCacheService serviceWith(ChatMessage... messages) {
        return new SessionCacheService(new PrefilledStore(List.of(messages)), Duration.ofSeconds(60));
    }

    static final class PrefilledStore implements SessionCacheStore {
        private final Map<String, List<ChatMessage>> data = new ConcurrentHashMap<>();

        PrefilledStore(List<ChatMessage> seed) {
            data.put("s1", new ArrayList<>(seed));
            data.put("new-session", new ArrayList<>(seed)); // 同样可命中空/有
        }

        @Override
        public List<ChatMessage> load(String sessionId) {
            return new ArrayList<>(data.getOrDefault(sessionId, List.of()));
        }

        @Override
        public void append(String sessionId, List<ChatMessage> messages, Duration ttl) {
            data.computeIfAbsent(sessionId, k -> new ArrayList<>()).addAll(messages);
        }
    }

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
