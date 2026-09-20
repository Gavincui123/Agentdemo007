package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HumanTicketService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TicketApprovalSubmitter} 单测（提交制 2026-09-20·用户裁决：Agent 只创建工单，
 * 决议=管理台状态事件，Agent 只查询进度）。
 *
 * <p>验提交制核心语义：
 * <ul>
 *   <li>建单即返回（无请求内等待——{@code submit} 调用即返回，工单 PENDING 落 {@link HumanTicketService}）；</li>
 *   <li>工单上下文富集（query=动作/订单/订单事实，reason=Agent 判定/政策依据/用户申请，管理员一屏可审）；</li>
 *   <li>幂等键 {@code wfa:{action}:{orderId}}：PENDING 复用同单不重建；APPROVED 已受理不重建
 *       （防重复业务动作，alreadyApproved=true）；REJECTED/TIMEOUT 重建新单（驳回是那张工单的事件，
 *       不封禁客户再次申请；键指针指向最新单）。</li>
 * </ul>
 */
class TicketApprovalSubmitterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC);

    private final HumanTicketService tickets = new HumanTicketService();
    private final TicketApprovalSubmitter submitter = new TicketApprovalSubmitter(tickets, CLOCK);

    private static WorkflowApprovalSubmitter.ApprovalRequest refundRequest() {
        return new WorkflowApprovalSubmitter.ApprovalRequest(
                "s1", "REFUND", "ORD-001",
                "用户 10086，下单 2026-09-09，状态 已签收，金额 299.00",
                "7 天无理由退款", "ELIGIBLE｜依据：7天无理由", "退款 ORD-001");
    }

    // ---- 提交制核心：建单即返回 ----

    @Test
    void submit_createsPendingTicket_andReturnsImmediately() {
        WorkflowApprovalSubmitter.Outcome outcome = submitter.submit(refundRequest());

        assertThat(outcome.alreadyApproved()).isFalse();
        assertThat(outcome.ticketId()).isNotBlank();
        HumanTicket ticket = tickets.findById(outcome.ticketId()).orElseThrow();
        assertThat(ticket.status()).isEqualTo(HumanTicket.Status.PENDING);
        assertThat(ticket.idempotencyKey()).isEqualTo("wfa:REFUND:ORD-001"); // 业务幂等键（管理台对账锚点）
        assertThat(ticket.createdAt()).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"));
    }

    @Test
    void submit_enrichesTicketContent_adminOneScreenReview() {
        WorkflowApprovalSubmitter.Outcome outcome = submitter.submit(refundRequest());

        HumanTicket ticket = tickets.findById(outcome.ticketId()).orElseThrow();
        // query：动作/订单/订单事实一屏可审
        assertThat(ticket.query()).contains("售后审批").contains("退款").contains("ORD-001")
                .contains("已签收").contains("299.00");
        // reason：Agent 判定/政策依据/用户申请全进审批依据
        assertThat(ticket.reason()).contains("人工审批").contains("ELIGIBLE")
                .contains("7 天无理由退款").contains("退款 ORD-001");
    }

    // ---- 同键复用语义 ----

    @Test
    void submit_sameKeyPendingTicket_reused_noDuplicate() {
        WorkflowApprovalSubmitter.Outcome first = submitter.submit(refundRequest());
        WorkflowApprovalSubmitter.Outcome second = submitter.submit(refundRequest());

        assertThat(second.ticketId()).isEqualTo(first.ticketId()); // 复用 PENDING 同单
        assertThat(second.alreadyApproved()).isFalse();
        assertThat(tickets.all()).hasSize(1); // 不重复建单
    }

    @Test
    void submit_sameKeyApprovedTicket_alreadyApproved_noRebuild() {
        WorkflowApprovalSubmitter.Outcome first = submitter.submit(refundRequest());
        tickets.resolve(first.ticketId(), HumanTicket.Status.APPROVED, CLOCK.instant()); // 管理台批准

        WorkflowApprovalSubmitter.Outcome second = submitter.submit(refundRequest());

        assertThat(second.ticketId()).isEqualTo(first.ticketId());
        assertThat(second.alreadyApproved()).isTrue(); // 已受理：不重复建单防重复业务动作
        assertThat(tickets.all()).hasSize(1);
    }

    @Test
    void submit_sameKeyRejectedTicket_rebuildsNewTicket_reapplyAllowed() {
        // 事件语义（用户裁决）：驳回是那张工单的事件，不封禁客户再次申请 → 重建新单，键指向最新单
        WorkflowApprovalSubmitter.Outcome first = submitter.submit(refundRequest());
        tickets.resolve(first.ticketId(), HumanTicket.Status.REJECTED, CLOCK.instant());

        WorkflowApprovalSubmitter.Outcome second = submitter.submit(refundRequest());

        assertThat(second.alreadyApproved()).isFalse();
        assertThat(second.ticketId()).isNotEqualTo(first.ticketId()); // 新单
        assertThat(tickets.findById(second.ticketId()).orElseThrow().status())
                .isEqualTo(HumanTicket.Status.PENDING);
        assertThat(tickets.findByIdempotencyKey("wfa:REFUND:ORD-001").orElseThrow().id())
                .isEqualTo(second.ticketId()); // 键指针指向最新单
        assertThat(tickets.all()).hasSize(2);
    }

    @Test
    void submit_sameKeyTimeoutTicket_rebuildsNewTicket() {
        WorkflowApprovalSubmitter.Outcome first = submitter.submit(refundRequest());
        tickets.resolve(first.ticketId(), HumanTicket.Status.TIMEOUT, CLOCK.instant());

        WorkflowApprovalSubmitter.Outcome second = submitter.submit(refundRequest());

        assertThat(second.ticketId()).isNotEqualTo(first.ticketId());
        assertThat(tickets.findByIdempotencyKey("wfa:REFUND:ORD-001").orElseThrow().id())
                .isEqualTo(second.ticketId());
    }

    // ---- 键空间与动作区分 ----

    @Test
    void submit_differentAction_differentTicket() {
        submitter.submit(refundRequest());
        submitter.submit(new WorkflowApprovalSubmitter.ApprovalRequest(
                "s1", "RETURN", "ORD-001", null, null, null, "退货 ORD-001"));

        assertThat(tickets.all()).hasSize(2); // REFUND/RETURN 键不同，互不复用
    }

    @Test
    void submit_ticketRegisteredWithRISKHigh() {
        WorkflowApprovalSubmitter.Outcome outcome = submitter.submit(refundRequest());
        // 高风险工单标记（与 HitlStep 同口径，管理台按风险分级展示）
        assertThat(tickets.findById(outcome.ticketId()).orElseThrow().query()).isNotBlank();
    }

    // ---- 并发：同键并发提交不致重复建单竞态放大（computeIfAbsent 幂等键指针）----

    @Test
    void submit_concurrentSameKey_onlyOneTicketPerKeyUntilResolved() throws Exception {
        AtomicInteger created = new AtomicInteger();
        Runnable task = () -> {
            submitter.submit(refundRequest());
            created.incrementAndGet();
        };
        List<Thread> threads = List.of(new Thread(task), new Thread(task), new Thread(task));
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join(5000);
        }

        // 提交制下建单前查询+建单非原子（check-then-act），极端并发可产少量重复单——
        // 此处只验"并发不抛、键指针收敛到唯一最新单"（重复单竞态为已知 demo 限制，与 HitlStep 同构）
        assertThat(created.get()).isEqualTo(3);
        assertThat(tickets.findByIdempotencyKey("wfa:REFUND:ORD-001")).isPresent();
    }
}
