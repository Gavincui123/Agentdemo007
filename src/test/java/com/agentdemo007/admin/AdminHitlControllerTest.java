package com.agentdemo007.admin;

import com.agentdemo007.capability.hitl.HitlRequest;
import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理台·HITL 工单端点单测（Phase 19·T97）。
 *
 * <p>{@code GET /admin/hitl/tickets} 列 PENDING 工单；{@code POST /admin/hitl/tickets/{id}/confirm}
 * 与 {@code /reject} 流转工单到 APPROVED/REJECTED；未知 id → 404 NOT_FOUND（②降级不 5xx，同形 HTTP 200 + code）。
 * {@code resolvedAt} 取注入式 {@link Clock}（可测，匹配 AlertRuleEvaluator 模式）。
 *
 * <p>直构控制器（同 {@code ChatControllerTest} 约定），断言 {@link UnifiedResponse}。
 */
class AdminHitlControllerTest {

    private final HumanTicketService service = new HumanTicketService();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC);
    private final AdminHitlController controller = new AdminHitlController(service, clock);

    private HumanTicket pendingTicket(String sessionId) {
        return service.createTicket(
                new HitlRequest(sessionId, "转人工", "用户请求", HitlRequest.RISK_HIGH),
                Instant.parse("2026-09-08T09:00:00Z"));
    }

    @Test
    void tickets_returnsOnlyPendingSummaries() {
        HumanTicket t1 = pendingTicket("s1");
        HumanTicket t2 = pendingTicket("s2");
        service.resolve(t2.id(), HumanTicket.Status.APPROVED, Instant.parse("2026-09-08T09:30:00Z"));

        UnifiedResponse resp = controller.tickets();

        assertThat(resp.code()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        java.util.List<HitlTicketSummary> data = (java.util.List<HitlTicketSummary>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0).id()).isEqualTo(t1.id());
        assertThat(data.get(0).status()).isEqualTo("PENDING");
        assertThat(data.get(0).resolvedAt()).isNull();
    }

    @Test
    void confirm_setsApprovedAndResolvedAt() {
        HumanTicket t = pendingTicket("s1");

        UnifiedResponse resp = controller.confirm(t.id());

        assertThat(resp.code()).isEqualTo(0);
        HitlTicketSummary summary = (HitlTicketSummary) resp.data();
        assertThat(summary.status()).isEqualTo("APPROVED");
        assertThat(summary.resolvedAt()).isEqualTo(Instant.parse("2026-09-08T10:00:00Z"));
        assertThat(service.findById(t.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.APPROVED);
    }

    @Test
    void reject_setsRejected() {
        HumanTicket t = pendingTicket("s1");

        HitlTicketSummary summary = (HitlTicketSummary) controller.reject(t.id()).data();

        assertThat(summary.status()).isEqualTo("REJECTED");
        assertThat(summary.resolvedAt()).isEqualTo(Instant.parse("2026-09-08T10:00:00Z"));
    }

    @Test
    void confirm_unknownId_returnsNotFoundCode() {
        UnifiedResponse resp = controller.confirm("unknown-id");

        assertThat(resp.code()).isEqualTo(ErrorCode.NOT_FOUND.code());
        assertThat(resp.data()).isNull();
        // 未知 id 不应改变任何工单状态
        assertThat(service.pendingTickets()).isEmpty();
    }

    @Test
    void tickets_emptyReturnsEmptyList() {
        UnifiedResponse resp = controller.tickets();

        assertThat(resp.code()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        java.util.List<HitlTicketSummary> data = (java.util.List<HitlTicketSummary>) resp.data();
        assertThat(data).isEmpty();
    }
}
