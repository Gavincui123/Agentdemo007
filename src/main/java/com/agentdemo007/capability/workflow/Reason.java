package com.agentdemo007.capability.workflow;

/**
 * 售后校验失败原因（[[business-tools-workflow-dag]] §2.3·校验失败按情况告知客户）。
 *
 * <p>{@link AfterSaleWorkflowOutcome.Rejected} 携带此枚举，{@code WorkflowExecutionStep}（Slice 4）
 * 据此产 presetReply 话术短路（业务驳回≠系统失败，E1 决策：不 ShortCircuit）。
 */
public enum Reason {
    /** 订单不存在（query_order 召回空）。 */
    ORDER_NOT_FOUND,
    /** 订单非当前账户所有（order.userId ≠ currentUserId）。 */
    ORDER_NOT_OWNED,
    /** 超过 7 天无理由退货窗口（return 流）。 */
    BEYOND_7_DAY,
    /** 超过退款办理窗口（refund 流）。 */
    REFUND_WINDOW_EXPIRED
}
