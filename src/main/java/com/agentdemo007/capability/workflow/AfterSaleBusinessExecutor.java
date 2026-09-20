package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.hitl.HumanTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 售后业务执行 mock（2026-09-20 原则翻转·用户裁决：业务流程是业务系统内部运作，Agent 最小权限）。
 *
 * <p>管理台对工作流审批工单（{@code wfa:} 键）决议为 APPROVED 后由控制器触发本执行器——
 * <b>这是业务系统的事件消费入口，不是 Agent 链路的一环</b>：Agent 只建单与查工单，
 * 真正的退款/退货办理在业务系统内部完成。demo 以日志留痕（真售后后端=迭代后补）；
 * 后续对客户的结果告知走工单状态查询（{@code TicketStatusQueryTool}），不经本类回灌。
 */
@Component
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class AfterSaleBusinessExecutor {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleBusinessExecutor.class);

    /** 审批批准事件的业务系统消费（mock：日志留痕；真售后后端=迭代后补）。 */
    public void onApproved(HumanTicket ticket) {
        log.info("售后业务执行（业务系统 mock 留痕）：ticket={} key={} query={}",
                ticket.id(), ticket.idempotencyKey(), ticket.query());
    }
}
