package com.agentdemo007.capability.workflow;

/**
 * 售后校验失败原因（[[business-tools-workflow-dag]] §2.3·校验失败按情况告知客户）。
 *
 * <p>{@link AfterSaleWorkflowOutcome.Rejected} 携带此枚举，{@code WorkflowExecutionStep} 据此产
 * presetReply 话术短路（业务驳回≠系统失败，E1 决策：不 ShortCircuit）。
 *
 * <p><b>生产化定案（2026-09-19）</b>：政策适用性（超无理由期限/活动商品限制等）不再硬编码窗口
 * 规则（原 BEYOND_7_DAY/REFUND_WINDOW_EXPIRED 退役），统一交 {@link AfterSaleEligibilityJudge}
 * Agent 裁决——政策类驳回走 {@link #POLICY_INELIGIBLE}，话术由 Agent 依政策原文生成。
 * 机械事实校验（订单存在/归属）保留枚举。
 */
public enum Reason {
    /** 订单不存在（query_order 召回空）。 */
    ORDER_NOT_FOUND,
    /** 订单非当前账户所有（order.userId ≠ currentUserId）。 */
    ORDER_NOT_OWNED,
    /** 政策资格不满足（Agent 依政策知识裁决；话术见 {@code Rejected.customerMessage}）。 */
    POLICY_INELIGIBLE
}
