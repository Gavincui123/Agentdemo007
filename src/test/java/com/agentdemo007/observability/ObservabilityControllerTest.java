package com.agentdemo007.observability;

import com.agentdemo007.capability.hitl.HitlRequest;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.response.UnifiedResponse;
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
 * 可观测端点单测（Phase 19·T99）。
 *
 * <p>{@code GET /api/obs/summary}：经 {@link ObservabilitySummaryCollector} 实时聚合 →
 * {@link UnifiedResponse#success} 强类型收口透出。直构控制器 + 真实采集器/MeterRegistry/
 * {@link ModelConfigCenter}/{@link HumanTicketService}（真实读路径，非 mock 行为），仅 mock
 * {@link ChatTurnRepository}（无 H2 的单测不可连库）。采集器聚合语义由
 * {@link ObservabilitySummaryCollectorTest} 全字段守卫，此处只验端点收口：{@code code=0} +
 * {@code data} 为 {@link ObservabilitySummary} 且字段可读。
 */
class ObservabilityControllerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ModelRegistry registry = new ModelRegistry();
    private final ModelConfigCenter configCenter = new ModelConfigCenter(() -> null, registry);
    private final HumanTicketService ticketService = new HumanTicketService();
    private final ChatTurnRepository turnRepository = mock(ChatTurnRepository.class);
    private final ObservabilitySummaryCollector collector =
            new ObservabilitySummaryCollector(meterRegistry, configCenter, ticketService, turnRepository);
    private final ObservabilityController controller = new ObservabilityController(collector);
    private final AgentMetrics metrics = new AgentMetrics(meterRegistry);

    @Test
    void summary_returnsUnifiedResponseWithLiveSnapshot() {
        metrics.recordChatRequest();
        metrics.recordChatRequest();
        metrics.recordDegradation(DegradationScenario.MODEL_DOWN);
        metrics.recordOutcome(AgentMetrics.Outcome.DEGRADED, DegradationScenario.MODEL_DOWN);
        registry.register(ModelMetadata.builder("m1").weight(1).build());
        registry.register(ModelMetadata.builder("m2").status(ModelStatus.DISABLED).build());
        ticketService.createTicket(
                new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH), Instant.parse("2026-09-08T10:00:00Z"));
        when(turnRepository.count()).thenReturn(7L);
        when(turnRepository.countByDegradedTrue()).thenReturn(2L);

        UnifiedResponse resp = controller.summary();

        assertThat(resp.code()).isEqualTo(0);
        ObservabilitySummary s = (ObservabilitySummary) resp.data();
        assertThat(s.chatRequests()).isEqualTo(2);
        assertThat(s.degradationTotal()).isEqualTo(1);
        assertThat(s.outcomeDegraded()).isEqualTo(1);
        assertThat(s.modelCount()).isEqualTo(2);
        assertThat(s.modelEnabled()).isEqualTo(1);
        assertThat(s.hitlPendingTickets()).isEqualTo(1);
        assertThat(s.totalTurns()).isEqualTo(7);
        assertThat(s.degradedTurns()).isEqualTo(2);
    }

    @Test
    void summary_emptyState_returnsZerosNotError() {
        UnifiedResponse resp = controller.summary();

        assertThat(resp.code()).isEqualTo(0); // ②每步降级：空指标端点恒可用
        ObservabilitySummary s = (ObservabilitySummary) resp.data();
        assertThat(s.chatRequests()).isZero();
        assertThat(s.degradationByScenario()).isEmpty();
        assertThat(s.modelCount()).isZero();
        assertThat(s.totalTurns()).isZero();
    }
}
