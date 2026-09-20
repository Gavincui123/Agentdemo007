package com.agentdemo007.capability.hitl;

import com.agentdemo007.persistence.entity.HitlTicketEntity;
import com.agentdemo007.persistence.repository.HitlTicketRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        // 未知工单不抛异常（幂等防御，不阻塞主链路）——语义为 CONFLICT（状态机无从转移）
        HumanTicketService.ResolveResult r =
                service.resolve("unknown", HumanTicket.Status.APPROVED, Instant.now());
        assertThat(r).isEqualTo(HumanTicketService.ResolveResult.CONFLICT);
    }

    // ---- 2026-09-18 L2：状态机守卫（决议幂等/防回翻）+ 业务幂等键索引 ----

    @Test
    void resolveTransitionThenRepeat_idempotentRepeat_noSideEffect() {
        Instant created = Instant.parse("2026-09-05T10:00:00Z");
        HumanTicket t = service.createTicket(new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH), created);

        assertThat(service.resolve(t.id(), HumanTicket.Status.APPROVED, created.plusSeconds(60)))
                .isEqualTo(HumanTicketService.ResolveResult.TRANSITIONED);
        assertThat(service.resolve(t.id(), HumanTicket.Status.APPROVED, created.plusSeconds(120)))
                .isEqualTo(HumanTicketService.ResolveResult.IDEMPOTENT_REPEAT); // 双击/重放
        HumanTicket updated = service.findById(t.id()).orElseThrow();
        assertThat(updated.resolvedAt()).isEqualTo(created.plusSeconds(60)); // 首次决议时刻不被刷新
    }

    @Test
    void resolveOpposite_conflict_stateNotFlipped() {
        Instant created = Instant.parse("2026-09-05T10:00:00Z");
        HumanTicket t = service.createTicket(new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH), created);
        service.resolve(t.id(), HumanTicket.Status.APPROVED, created.plusSeconds(60));

        assertThat(service.resolve(t.id(), HumanTicket.Status.REJECTED, created.plusSeconds(120)))
                .isEqualTo(HumanTicketService.ResolveResult.CONFLICT);
        assertThat(service.findById(t.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.APPROVED);
    }

    @Test
    void markTimeout_afterResolved_conflict() {
        Instant created = Instant.parse("2026-09-05T10:00:00Z");
        HumanTicket t = service.createTicket(new HitlRequest("s", "q", "r", HitlRequest.RISK_HIGH), created);
        service.resolve(t.id(), HumanTicket.Status.APPROVED, created.plusSeconds(60));

        service.markTimeout(t.id(), created.plusSeconds(120)); // 已决议单不得被超时复活

        assertThat(service.findById(t.id()).orElseThrow().status()).isEqualTo(HumanTicket.Status.APPROVED);
    }

    @Test
    void idempotencyKey_indexRoundTrip_andRemapOnRecreate() {
        Instant created = Instant.parse("2026-09-05T10:00:00Z");
        HumanTicket t1 = service.createTicket(
                new HitlRequest("s1", "退款 ORD-001", "r", HitlRequest.RISK_HIGH), created, "hitl:refund:ORD-001");

        assertThat(service.findByIdempotencyKey("hitl:refund:ORD-001")).isPresent();
        assertThat(service.findByIdempotencyKey("hitl:refund:ORD-001").orElseThrow().id()).isEqualTo(t1.id());
        assertThat(service.findByIdempotencyKey("no-such-key")).isEmpty();
        assertThat(service.findByIdempotencyKey(null)).isEmpty(); // 无键安全

        // 超时后重建：同键新单，索引指向最新
        service.markTimeout(t1.id(), created.plusSeconds(3600));
        HumanTicket t2 = service.createTicket(
                new HitlRequest("s2", "退款 ORD-001", "r", HitlRequest.RISK_HIGH),
                created.plusSeconds(7200), "hitl:refund:ORD-001");

        assertThat(service.findByIdempotencyKey("hitl:refund:ORD-001").orElseThrow().id()).isEqualTo(t2.id());
        assertThat(service.findById(t1.id()).orElseThrow().idempotencyKey()).isEqualTo("hitl:refund:ORD-001"); // 旧单留痕
    }

    // ---- 2026-09-18 同日扩展（用户裁决）：工单 DB 持久副本（异步落库 + 重启回源）----

    @Test
    void createTicket_persistedToDbAsync() {
        HitlTicketRepository repo = mock(HitlTicketRepository.class);
        when(repo.findByTicketId(any())).thenReturn(Optional.empty());
        HumanTicketService dbService = new HumanTicketService(repo, Runnable::run);

        HumanTicket t = dbService.createTicket(
                new HitlRequest("s1", "退款 ORD-001", "r", HitlRequest.RISK_HIGH),
                Instant.parse("2026-09-05T10:00:00Z"), "hitl:REFUND:ORD-001");

        ArgumentCaptor<HitlTicketEntity> cap = ArgumentCaptor.forClass(HitlTicketEntity.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getTicketId()).isEqualTo(t.id());
        assertThat(cap.getValue().getIdempotencyKey()).isEqualTo("hitl:REFUND:ORD-001");
        assertThat(cap.getValue().getStatus()).isEqualTo(HumanTicket.Status.PENDING);
    }

    @Test
    void resolve_persistsTransitionToDb() {
        HitlTicketRepository repo = mock(HitlTicketRepository.class);
        when(repo.findByTicketId(any())).thenReturn(Optional.empty());
        HumanTicketService dbService = new HumanTicketService(repo, Runnable::run);
        HumanTicket t = dbService.createTicket(
                new HitlRequest("s1", "q", "r", HitlRequest.RISK_HIGH), Instant.now());

        dbService.resolve(t.id(), HumanTicket.Status.APPROVED, Instant.parse("2026-09-05T11:00:00Z"));

        ArgumentCaptor<HitlTicketEntity> cap = ArgumentCaptor.forClass(HitlTicketEntity.class);
        verify(repo, org.mockito.Mockito.times(2)).save(cap.capture()); // 建单 + 决议各一次
        assertThat(cap.getAllValues().get(1).getStatus()).isEqualTo(HumanTicket.Status.APPROVED);
        assertThat(cap.getAllValues().get(1).getResolvedAt()).isEqualTo(Instant.parse("2026-09-05T11:00:00Z"));
    }

    @Test
    void findById_rehydratesFromDb_afterRestart() {
        // 重启恢复路径：内存空 → 按 ticket_id 回源 DB 重建（恢复入口）
        HitlTicketRepository repo = mock(HitlTicketRepository.class);
        HitlTicketEntity row = HitlTicketEntity.from(new HumanTicket(
                "t-db-1", "sess-9", "退款 ORD-001", "r", HumanTicket.Status.PENDING,
                Instant.parse("2026-09-05T10:00:00Z"), null, "hitl:REFUND:ORD-001"));
        when(repo.findByTicketId("t-db-1")).thenReturn(Optional.of(row));
        HumanTicketService dbService = new HumanTicketService(repo, Runnable::run);

        Optional<HumanTicket> found = dbService.findById("t-db-1");

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().idempotencyKey()).isEqualTo("hitl:REFUND:ORD-001");
    }

    @Test
    void findByIdempotencyKey_rehydratesNewestFromDb_andfindByIdDoesNotStealKeyPointer() {
        // 按键回源取最新单；按 id 回源的旧单不得抢占键指针（TIMEOUT 重建语义跨重启保持）
        HitlTicketRepository repo = mock(HitlTicketRepository.class);
        HumanTicket older = new HumanTicket("t-old", "s1", "q", "r", HumanTicket.Status.PENDING,
                Instant.parse("2026-09-05T10:00:00Z"), null, "hitl:REFUND:ORD-001");
        HumanTicket newer = new HumanTicket("t-new", "s2", "q", "r", HumanTicket.Status.PENDING,
                Instant.parse("2026-09-05T12:00:00Z"), null, "hitl:REFUND:ORD-001");
        when(repo.findByIdempotencyKeyOrderByCreatedAtDesc("hitl:REFUND:ORD-001"))
                .thenReturn(List.of(HitlTicketEntity.from(newer), HitlTicketEntity.from(older)));
        when(repo.findByTicketId("t-old")).thenReturn(Optional.of(HitlTicketEntity.from(older)));
        HumanTicketService dbService = new HumanTicketService(repo, Runnable::run);

        assertThat(dbService.findByIdempotencyKey("hitl:REFUND:ORD-001").orElseThrow().id()).isEqualTo("t-new");
        dbService.findById("t-old"); // 回源旧单
        assertThat(dbService.findByIdempotencyKey("hitl:REFUND:ORD-001").orElseThrow().id()).isEqualTo("t-new");
    }

    @Test
    void pendingTickets_mergesDbPendingRows_afterRestart() {
        // 管理台待审批列表：内存空 → DB PENDING 行回源合并（重启不丢单）
        HitlTicketRepository repo = mock(HitlTicketRepository.class);
        HitlTicketEntity pending = HitlTicketEntity.from(new HumanTicket(
                "t-db-p", "sess-1", "q", "r", HumanTicket.Status.PENDING,
                Instant.parse("2026-09-05T10:00:00Z"), null, "hitl:REFUND:ORD-001"));
        when(repo.findByStatus(HumanTicket.Status.PENDING)).thenReturn(List.of(pending));
        HumanTicketService dbService = new HumanTicketService(repo, Runnable::run);

        List<HumanTicket> pendingList = dbService.pendingTickets();

        assertThat(pendingList).hasSize(1);
        assertThat(pendingList.get(0).id()).isEqualTo("t-db-p");
    }

    @Test
    void dbWriteFailure_degradesToMemory_warnNotThrow() {
        // §5.12 降级：落库失败仅告警，内存状态机不受影响
        HitlTicketRepository repo = mock(HitlTicketRepository.class);
        when(repo.findByTicketId(any())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db down")).when(repo).save(any(HitlTicketEntity.class));
        HumanTicketService dbService = new HumanTicketService(repo, Runnable::run);

        HumanTicket t = dbService.createTicket(
                new HitlRequest("s1", "q", "r", HitlRequest.RISK_HIGH), Instant.now());

        assertThat(dbService.findById(t.id())).isPresent();
        assertThat(dbService.resolve(t.id(), HumanTicket.Status.APPROVED, Instant.now()))
                .isEqualTo(HumanTicketService.ResolveResult.TRANSITIONED);
    }
}
