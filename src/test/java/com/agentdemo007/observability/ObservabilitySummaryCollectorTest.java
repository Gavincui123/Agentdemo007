package com.agentdemo007.observability;

import com.agentdemo007.capability.hitl.HitlRequest;
import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.config.ModelMetadata.ModelStatus;
import com.agentdemo007.gateway.registry.ModelRegistry;
import com.agentdemo007.persistence.repository.ChatTurnRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可观测摘要采集器单测（Phase 19·T99）。
 *
 * <p>{@link ObservabilitySummaryCollector} 从真实 {@link io.micrometer.core.instrument.MeterRegistry}
 * （{@link AgentMetrics} 同一实例的稳定命名计数器）+ 模型/HITL/会话计数 派生强类型快照
 * {@link ObservabilitySummary}（④收口：强类型字段，非 Map）。计数器读法：按 meter 名聚合 counter，
 * 按 tag 维度分桶。空指标→0（②每步降级：未埋点不抛）。
 */
class ObservabilitySummaryCollectorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(meterRegistry);
    private final ModelRegistry modelRegistry = new ModelRegistry();
    private final ModelConfigCenter configCenter = new ModelConfigCenter(() -> null, modelRegistry);
    private final HumanTicketService ticketService = new HumanTicketService();
    private final ChatTurnRepository turnRepository = mock(ChatTurnRepository.class);
    private final ObservabilitySummaryCollector collector =
            new ObservabilitySummaryCollector(meterRegistry, configCenter, ticketService, turnRepository);

    @Test
    void summary_reflectsLiveCountersAndStructuralCounts() {
        metrics.recordChatRequest();
        metrics.recordChatRequest();
        metrics.recordDegradation(DegradationScenario.MODEL_DOWN);
        metrics.recordOutcome(AgentMetrics.Outcome.OK, null);
        metrics.recordOutcome(AgentMetrics.Outcome.DEGRADED, DegradationScenario.MODEL_DOWN);
        metrics.recordRag(true);
        metrics.recordRag(false);
        metrics.recordTool(false);
        metrics.recordHitl(true);
        metrics.recordFailover(true); // exhausted
        modelRegistry.register(ModelMetadata.builder("m1").weight(1).build());
        modelRegistry.register(ModelMetadata.builder("m2").status(ModelStatus.DISABLED).build());
        ticketService.createTicket(
                new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH), Instant.parse("2026-09-08T10:00:00Z"));
        when(turnRepository.count()).thenReturn(10L);
        when(turnRepository.countByDegradedTrue()).thenReturn(3L);

        ObservabilitySummary s = collector.summary();

        assertThat(s.chatRequests()).isEqualTo(2);
        assertThat(s.degradationTotal()).isEqualTo(1);
        assertThat(s.degradationByScenario()).containsEntry("MODEL_DOWN", 1L);
        assertThat(s.outcomeOk()).isEqualTo(1);
        assertThat(s.outcomeDegraded()).isEqualTo(1);
        assertThat(s.outcomeShortCircuit()).isZero();
        assertThat(s.ragHit()).isEqualTo(1);
        assertThat(s.ragMiss()).isEqualTo(1);
        assertThat(s.toolFailure()).isEqualTo(1);
        assertThat(s.hitlTriggered()).isEqualTo(1);
        assertThat(s.failoverExhausted()).isEqualTo(1);
        assertThat(s.modelCount()).isEqualTo(2);
        assertThat(s.modelEnabled()).isEqualTo(1);
        assertThat(s.hitlPendingTickets()).isEqualTo(1);
        assertThat(s.totalTurns()).isEqualTo(10);
        assertThat(s.degradedTurns()).isEqualTo(3);
    }

    @Test
    void summary_emptyState_returnsZerosNotErrors() {
        ObservabilitySummary s = collector.summary();

        assertThat(s.chatRequests()).isZero();
        assertThat(s.degradationTotal()).isZero();
        assertThat(s.degradationByScenario()).isEmpty();
        assertThat(s.modelCount()).isZero();
        assertThat(s.hitlPendingTickets()).isZero();
        assertThat(s.totalTurns()).isZero();
    }
}
