package com.agentdemo007.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * AgentMetrics 指标收口门面单测（Phase 15·全链路可观测）。
 *
 * <p>④统一收口：所有指标只经此门面记录（同 {@link com.agentdemo007.common.pipeline.StepOutcomeAuditor}
 * 之于审计），禁止散落 {@link MeterRegistry} 直接打点导致命名/标签漂移。本类先锁门面契约——
 * 每个记录方法产出一个稳定命名的 meter（counter/timer），标签维度由场景语义决定。
 */
class AgentMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(registry);

    @Test
    void recordChatRequest_incrementsNamedCounter() {
        metrics.recordChatRequest();
        metrics.recordChatRequest();

        assertThat(registry.counter("agent.chat.requests").count()).isEqualTo(2.0);
    }

    @Test
    void recordDegradation_incrementsCounterTaggedByScenario() {
        metrics.recordDegradation(DegradationScenario.RAG_SKIP);
        metrics.recordDegradation(DegradationScenario.RAG_SKIP);
        metrics.recordDegradation(DegradationScenario.SESSION_DOWN);

        assertThat(registry.counter("agent.degradation", "scenario", "RAG_SKIP").count())
                .isEqualTo(2.0);
        assertThat(registry.counter("agent.degradation", "scenario", "SESSION_DOWN").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordPipelineDuration_recordsTimerWithCountAndAmount() {
        metrics.recordPipelineDuration(150);
        metrics.recordPipelineDuration(250);

        Timer timer = registry.timer("agent.pipeline.duration");
        assertThat(timer.count()).isEqualTo(2L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isCloseTo(400.0, within(1.0));
    }

    @Test
    void recordOutcome_incrementsCounterTaggedByOutcomeAndScenario() {
        metrics.recordOutcome(AgentMetrics.Outcome.OK, null);
        metrics.recordOutcome(AgentMetrics.Outcome.DEGRADED, DegradationScenario.RAG_SKIP);
        metrics.recordOutcome(AgentMetrics.Outcome.SHORT_CIRCUIT, DegradationScenario.INTERNAL);

        assertThat(registry.counter("agent.pipeline.outcome", "outcome", "ok", "scenario", "none").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("agent.pipeline.outcome", "outcome", "degraded", "scenario", "RAG_SKIP").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("agent.pipeline.outcome", "outcome", "short_circuit", "scenario", "INTERNAL").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordIntent_incrementsCounterTaggedByIntent() {
        metrics.recordIntent(Intent.CHIT_CHAT);
        metrics.recordIntent(Intent.REASONING);

        assertThat(registry.counter("agent.intent", "intent", "CHIT_CHAT").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.intent", "intent", "REASONING").count()).isEqualTo(1.0);
    }

    @Test
    void recordRoute_incrementsCounterTaggedByRouteAndModel() {
        metrics.recordRoute(RouteRule.RouteType.REASONING, "gpt-4o");
        metrics.recordRoute(RouteRule.RouteType.SIMPLE, "gpt-4o-mini");

        assertThat(registry.counter("agent.route", "route", "REASONING", "model", "gpt-4o").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("agent.route", "route", "SIMPLE", "model", "gpt-4o-mini").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordModelCall_recordsTimerAndSuccessFailureCounter() {
        metrics.recordModelCall(150, true);
        metrics.recordModelCall(250, false);

        Timer timer = registry.timer("agent.gateway.call.duration");
        assertThat(timer.count()).isEqualTo(2L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isCloseTo(400.0, within(1.0));
        assertThat(registry.counter("agent.gateway.calls", "success", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.gateway.calls", "success", "false").count()).isEqualTo(1.0);
    }

    @Test
    void recordRag_incrementsCounterTaggedByHit() {
        metrics.recordRag(true);
        metrics.recordRag(false);

        assertThat(registry.counter("agent.rag", "hit", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.rag", "hit", "false").count()).isEqualTo(1.0);
    }

    @Test
    void recordTool_incrementsCounterTaggedBySuccess() {
        metrics.recordTool(true);
        metrics.recordTool(false);

        assertThat(registry.counter("agent.tool", "success", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.tool", "success", "false").count()).isEqualTo(1.0);
    }

    @Test
    void recordHitl_incrementsCounterTaggedByTriggered() {
        metrics.recordHitl(true);
        metrics.recordHitl(false);

        assertThat(registry.counter("agent.hitl", "triggered", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.hitl", "triggered", "false").count()).isEqualTo(1.0);
    }

    @Test
    void recordMqPublish_incrementsCounterTaggedByChannelAndSuccess() {
        metrics.recordMqPublish("history", true);
        metrics.recordMqPublish("audit", false);

        assertThat(registry.counter("agent.mq", "channel", "history", "success", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.mq", "channel", "audit", "success", "false").count()).isEqualTo(1.0);
    }

    @Test
    void recordFailover_incrementsCounterTaggedByOutcome() {
        metrics.recordFailover(false); // 成功转移
        metrics.recordFailover(true);  // 候选耗尽

        assertThat(registry.counter("agent.failover", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.failover", "outcome", "exhausted").count()).isEqualTo(1.0);
    }
}
