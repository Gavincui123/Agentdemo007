package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.hitl.HitlRequest;
import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HumanTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;

/**
 * 真工单提交实现（2026-09-20 原则翻转·用户裁决：Agent 只创建工单，决议=状态事件，Agent 只查询进度）。
 *
 * <p>链路：{@link #submit} 建一张 PENDING 人工工单（{@link HumanTicketService}，管理台
 * {@code /admin/hitl/tickets} 可见，业务幂等键 {@code wfa:{action}:{orderId}}）后<b>立即返回</b>。
 * 管理台 confirm/reject 只做工单状态变更（事件，不回灌请求线程——等待窗口/桥唤醒已整体退役，
 * 超时不可能影响决议）；批准事件由 {@link AfterSaleBusinessExecutor}（业务系统 mock）承接；
 * 客户后续经工单状态查询工具（{@code TicketStatusQueryTool}）读工单进度。
 *
 * <p>同业务键复用语义（镜像 HitlStep 建单前查询口径，差异：REJECTED 不封禁再申请）：
 * PENDING → 复用同单；APPROVED → 已受理不重建（防重复业务动作）；REJECTED/TIMEOUT →
 * 重建新单（驳回是那张工单的事件；键指针随新建指向最新单）。
 */
public class TicketApprovalSubmitter implements WorkflowApprovalSubmitter {

    private static final Logger log = LoggerFactory.getLogger(TicketApprovalSubmitter.class);

    /** 业务幂等键前缀（与 HitlStep 的 {@code hitl:} 键空间隔离；HitlBusinessGate 同形解析）。 */
    public static final String KEY_PREFIX = "wfa:";

    private final HumanTicketService tickets;
    private final Clock clock;

    public TicketApprovalSubmitter(HumanTicketService tickets, Clock clock) {
        this.tickets = tickets;
        this.clock = clock;
    }

    @Override
    public Outcome submit(ApprovalRequest request) {
        String key = KEY_PREFIX + request.action() + ":" + request.orderId();
        // 幂等锚定（镜像 HitlStep 建单前查询口径）：同业务键已有单则不重复建
        Optional<HumanTicket> existing = tickets.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            HumanTicket t = existing.get();
            switch (t.status()) {
                case APPROVED -> {
                    log.info("审批幂等（同键工单已批准，不重建单防重复业务动作）：key={} ticket={}", key, t.id());
                    return new Outcome(t.id(), true);
                }
                case PENDING -> {
                    log.info("审批复用 PENDING 同键工单：key={} ticket={}", key, t.id());
                    return new Outcome(t.id(), false);
                }
                case REJECTED, TIMEOUT -> { /* 那张工单已终局：事件语义下允许重建新单（键指向最新单） */ }
            }
        }
        HumanTicket ticket = tickets.createTicket(
                new HitlRequest(request.sessionId(), buildQuery(request), buildReason(request),
                        HitlRequest.RISK_HIGH),
                clock.instant(), key);
        log.info("售后审批工单已建（提交制，无请求内等待）：ticket={} key={} action={} orderId={}",
                ticket.id(), key, request.action(), request.orderId());
        return new Outcome(ticket.id(), false);
    }

    /** 工单 query（管理台待审列表首行展示）：动作/订单/订单事实一屏可审。 */
    private static String buildQuery(ApprovalRequest r) {
        String actionZh = "REFUND".equals(r.action()) ? "退款" : "退货";
        StringBuilder sb = new StringBuilder("售后审批｜").append(actionZh)
                .append("｜订单 ").append(nullToDash(r.orderId()));
        if (r.orderSummary() != null && !r.orderSummary().isBlank()) {
            sb.append("｜").append(r.orderSummary());
        }
        return sb.toString();
    }

    /** 工单 reason（审批依据）：Agent 资格判定 + 政策结论 + 用户申请原词。 */
    private static String buildReason(ApprovalRequest r) {
        StringBuilder sb = new StringBuilder("高风险售后工作流人工审批");
        if (r.eligibilityNote() != null && !r.eligibilityNote().isBlank()) {
            sb.append("｜Agent资格判定：").append(r.eligibilityNote());
        }
        if (r.policyConclusion() != null && !r.policyConclusion().isBlank()) {
            sb.append("｜政策依据：").append(r.policyConclusion());
        }
        if (r.userQuery() != null && !r.userQuery().isBlank()) {
            sb.append("｜用户申请：").append(r.userQuery());
        }
        return sb.toString();
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}
