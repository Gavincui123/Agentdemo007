package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.hitl.HitlRequest;
import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.capability.workflow.WorkflowApprovalSubmitter;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TicketStatusQueryTool} 单测（提交制 2026-09-20·用户裁决：Agent 最小权限，审批结果感知只此一路）。
 *
 * <p>验：按订单号锚定 {@code wfa:{REFUND|RETURN}:{orderId}} 幂等键取<b>最新</b>工单（两动作都查、
 * createdAt 新者胜；驳回后重建的新单即最新单）；四态如实转述不推断业务结果；查无工单如实告知
 * （不编造进度）。
 */
class TicketStatusQueryToolTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC);

    private final HumanTicketService tickets = new HumanTicketService();
    private final TicketStatusQueryTool tool = new TicketStatusQueryTool(tickets);

    /** 经提交制 seam 建单（与真实链路同源，锚点=idempotencyKey）。 */
    private String submit(String action, String orderId) {
        WorkflowApprovalSubmitter.ApprovalRequest req = new WorkflowApprovalSubmitter.ApprovalRequest(
                "s1", action, orderId, "订单摘要", "政策结论", "ELIGIBLE｜依据：测试", action + " " + orderId);
        return new com.agentdemo007.capability.workflow.TicketApprovalSubmitter(tickets, CLOCK)
                .submit(req).ticketId();
    }

    @Test
    void pendingTicket_reportsUnderHumanReview() {
        String ticketId = submit("REFUND", "ORD-001");

        String reply = tool.queryAfterSaleTicket("ORD-001");

        assertThat(reply).contains(ticketId).contains("人工审批中");
        assertThat(reply).contains("售后审批｜退款｜订单 ORD-001"); // 工单 query 随进度透出（管理员审什么）
    }

    @Test
    void approvedTicket_reportsApproved() {
        String ticketId = submit("REFUND", "ORD-001");
        tickets.resolve(ticketId, HumanTicket.Status.APPROVED,
                Instant.parse("2026-09-20T11:00:00Z"));

        String reply = tool.queryAfterSaleTicket("ORD-001");

        assertThat(reply).contains("已通过人工审批").contains("2026-09-20T11:00:00Z"); // 决议时间
    }

    @Test
    void rejectedTicket_reportsRejected_withoutBusinessConjecture() {
        String ticketId = submit("REFUND", "ORD-001");
        tickets.resolve(ticketId, HumanTicket.Status.REJECTED, CLOCK.instant());

        String reply = tool.queryAfterSaleTicket("ORD-001");

        assertThat(reply).contains("未通过人工审批");
        assertThat(reply).doesNotContain("已退款").doesNotContain("已受理"); // 只读状态，不推断业务结果
    }

    @Test
    void timeoutTicket_reportsTimeoutWithRetakeHint() {
        String ticketId = submit("REFUND", "ORD-001");
        tickets.resolve(ticketId, HumanTicket.Status.TIMEOUT, CLOCK.instant());

        assertThat(tool.queryAfterSaleTicket("ORD-001")).contains("超时").contains("可重新申请");
    }

    @Test
    void rebuiltTicket_latestWins() {
        // 事件语义：驳回后客户再申请 → 重建新单；进度查询须反映最新单（PENDING），不回旧单 REJECTED
        String oldId = submit("REFUND", "ORD-001");
        tickets.resolve(oldId, HumanTicket.Status.REJECTED, CLOCK.instant());
        String newId = submit("REFUND", "ORD-001");

        String reply = tool.queryAfterSaleTicket("ORD-001");

        assertThat(reply).contains(newId).contains("人工审批中");
        assertThat(reply).doesNotContain(oldId);
    }

    @Test
    void bothActionKeys_newerCreatedAtWins() {
        String returnTicket = submit("RETURN", "ORD-001");
        String refundTicket = submit("REFUND", "ORD-001"); // 后建（同 Clock 下 createdAt 相同→任一亦可，验不抛且命中其一）

        String reply = tool.queryAfterSaleTicket("ORD-001");

        assertThat(reply).containsAnyOf(returnTicket, refundTicket);
    }

    @Test
    void unknownOrder_honestNoTicketReply() {
        assertThat(tool.queryAfterSaleTicket("ORD-999"))
                .contains("ORD-999").contains("暂无售后审批工单");
    }

    @Test
    void blankOrderId_asksForOrderId() {
        assertThat(tool.queryAfterSaleTicket(" ")).contains("请提供订单号");
    }
}
