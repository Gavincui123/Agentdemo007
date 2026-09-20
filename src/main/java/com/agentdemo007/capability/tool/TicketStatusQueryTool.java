package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.hitl.HumanTicket;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.capability.workflow.TicketApprovalSubmitter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Optional;

/**
 * 售后工单进度查询 @Tool（2026-09-20 原则翻转·用户裁决：Agent 最小权限，审批结果感知只此一路）。
 *
 * <p>高风险售后走「提交制」：Agent 建工单即返回（{@link TicketApprovalSubmitter}），批准/驳回是
 * 管理台的工单状态事件；客户回头问进度时，Agent 经本工具<b>只读</b>工单状态如实转述——不代业务
 * 系统承诺结果、不感知业务执行细节（业务执行属 {@code AfterSaleBusinessExecutor} 内部运作）。
 * 查询锚点：订单号 → {@code wfa:{REFUND|RETURN}:{orderId}} 幂等键取最新工单（两动作都查，
 * 取 createdAt 更新者；驳回/超时后重建的新单即最新单）。
 */
@Component
public class TicketStatusQueryTool {

    private final HumanTicketService tickets;

    public TicketStatusQueryTool(HumanTicketService tickets) {
        this.tickets = tickets;
    }

    @Tool("按订单号查询售后审批工单进度（人工审批中/已通过/已驳回）；用户询问退款、退货办理进度或审批结果时调用")
    @ToolChannel(ToolCategory.RUNTIME)
    public String queryAfterSaleTicket(@P("订单号（例如 ORD-001）") String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return "请提供订单号（例如 ORD-001）以查询售后审批进度";
        }
        String id = orderId.trim().toUpperCase(java.util.Locale.ROOT);
        Optional<HumanTicket> refund = tickets.findByIdempotencyKey(TicketApprovalSubmitter.KEY_PREFIX + "REFUND:" + id);
        Optional<HumanTicket> ret = tickets.findByIdempotencyKey(TicketApprovalSubmitter.KEY_PREFIX + "RETURN:" + id);
        Optional<HumanTicket> latest = java.util.stream.Stream.of(refund, ret)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(HumanTicket::createdAt));
        return latest.map(TicketStatusQueryTool::describe)
                .orElse("订单 " + id + " 暂无售后审批工单（若刚提交请稍后查询，或确认订单号是否正确）");
    }

    /** 工单状态 → 进度话术（只读事实，不推断业务结果；管理员决议详情不外露）。 */
    private static String describe(HumanTicket t) {
        String statusZh = switch (t.status()) {
            case PENDING -> "人工审批中（已提交，请耐心等候）";
            case APPROVED -> "已通过人工审批";
            case REJECTED -> "未通过人工审批";
            case TIMEOUT -> "审批超时（可重新申请）";
        };
        StringBuilder sb = new StringBuilder("售后审批工单 ").append(t.id())
                .append("：").append(statusZh);
        if (t.resolvedAt() != null) {
            sb.append("，决议时间 ").append(t.resolvedAt());
        }
        sb.append("。").append(t.query());
        return sb.toString();
    }
}
