package com.agentdemo007.capability.hitl;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人工工单服务测试（第四层·HITL 工单创建与管理）。
 *
 * <p>覆盖 §5.12 HITL 行"工单"：触发即创建 PENDING 工单；可解析为 APPROVED/REJECTED/TIMEOUT。
 * 内存实现（{@link HumanTicketService}），prod 由 DB 持久化覆盖（Phase 13 审计/持久化接入）。
 */
class HumanTicketServiceTest {

    private final HumanTicketService service = new HumanTicketService();

    @Test
    void createTicket_pendingWithFields() {
        Instant now = Instant.parse("2026-09-05T10:00:00Z");
        HitlRequest req = new HitlRequest("sess-1", "我要转人工", "用户请求转人工服务", HitlRequest.RISK_HIGH);

        HumanTicket ticket = service.createTicket(req, now);

        assertThat(ticket.id()).isNotBlank();
        assertThat(ticket.sessionId()).isEqualTo("sess-1");
        assertThat(ticket.query()).isEqualTo("我要转人工");
        assertThat(ticket.reason()).isEqualTo("用户请求转人工服务");
        assertThat(ticket.status()).isEqualTo(HumanTicket.Status.PENDING);
        assertThat(ticket.createdAt()).isEqualTo(now);
        assertThat(ticket.resolvedAt()).isNull();
    }

    @Test
    void findById_roundTrips() {
        Instant now = Instant.parse("2026-09-05T10:00:00Z");
        HumanTicket ticket = service.createTicket(
                new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH), now);

        Optional<HumanTicket> found = service.findById(ticket.id());

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(ticket.id());
        assertThat(service.findById("unknown")).isEmpty();
    }

    @Test
    void pendingTickets_returnsOnlyPending() {
        Instant now = Instant.parse("2026-09-05T10:00:00Z");
        HumanTicket t1 = service.createTicket(new HitlRequest("s1", "q", "r", HitlRequest.RISK_HIGH), now);
        HumanTicket t2 = service.createTicket(new HitlRequest("s2", "q", "r", HitlRequest.RISK_HIGH), now);
        service.resolve(t2.id(), HumanTicket.Status.APPROVED, now);

        assertThat(service.pendingTickets()).hasSize(1);
        assertThat(service.pendingTickets().get(0).id()).isEqualTo(t1.id());
    }

    @Test
    void resolve_setsStatusAndResolvedAt() {
        Instant created = Instant.parse("2026-09-05T10:00:00Z");
        Instant resolved = Instant.parse("2026-09-05T10:05:00Z");
        HumanTicket ticket = service.createTicket(
                new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH), created);

        service.resolve(ticket.id(), HumanTicket.Status.APPROVED, resolved);

        HumanTicket updated = service.findById(ticket.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(HumanTicket.Status.APPROVED);
        assertThat(updated.resolvedAt()).isEqualTo(resolved);
        assertThat(service.pendingTickets()).isEmpty();
    }

    @Test
    void markTimeout_setsTimeoutStatus() {
        Instant created = Instant.parse("2026-09-05T10:00:00Z");
        Instant timeout = Instant.parse("2026-09-05T10:05:00Z");
        HumanTicket ticket = service.createTicket(
                new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH), created);

        service.markTimeout(ticket.id(), timeout);

        HumanTicket updated = service.findById(ticket.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(HumanTicket.Status.TIMEOUT);
        assertThat(updated.resolvedAt()).isEqualTo(timeout);
    }

    @Test
    void resolve_unknownId_noThrow() {
        // 未知工单不抛异常（幂等防御，不阻塞主链路）
        service.resolve("unknown", HumanTicket.Status.APPROVED, Instant.now());
    }
}
