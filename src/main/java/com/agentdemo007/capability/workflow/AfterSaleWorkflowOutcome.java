package com.agentdemo007.capability.workflow;

/**
 * 售后工作流终态（sealed·[[business-tools-workflow-dag]] §2.3·E1 决策）。
 *
 * <p>{@link AfterSaleWorkflowGraph#invoke} 返回此类型（从终态读 {@code OUTCOME_KEY}）。四态：
 * <ul>
 *   <li>{@link Approved}——validate pass + submit + 审批批准（售后动作完成）；</li>
 *   <li>{@link Rejected}——validate 失败（订单非本人/超窗/不存在），业务规则驳回，携带 {@link Reason} + 客户话术；
 *       <b>≠ {@link com.agentdemo007.common.degradation.DegradationScenario}</b>（业务驳回≠系统失败，E1：
 *       {@code WorkflowExecutionStep} 写 {@code presetReply}+Proceed，{@code OutputStep} 跳 LLM 话术短路）；</li>
 *   <li>{@link Denied}——审批恒驳回至 maxIterations 强制终止（护栏兜底）；</li>
 *   <li>{@link Timeout}——审批超时未决议（submit 已跑、售后已提交但未获批准，不假装成功）。</li>
 * </ul>
 */
public sealed interface AfterSaleWorkflowOutcome
        permits AfterSaleWorkflowOutcome.Approved, AfterSaleWorkflowOutcome.Rejected,
        AfterSaleWorkflowOutcome.Denied, AfterSaleWorkflowOutcome.Timeout {

    /**
     * validate pass + 审批批准：售后动作完成。approver 来自 {@link WorkflowApprovalDecision.Approved}；
     * orderStatus 来自 {@code query_order} 节点召回的 {@link com.agentdemo007.capability.business.OrderRecord#status()}；
     * policyConclusion 来自 {@code query_policy} 节点召回的 {@link com.agentdemo007.capability.business.PolicyFragment#text()}。
     */
    record Approved(String approver, String orderStatus, String policyConclusion) implements AfterSaleWorkflowOutcome {
        /** 兼容构造：无订单状态/政策结论（旧调用点/测试零改动）。 */
        public Approved(String approver) { this(approver, null, null); }
    }

    /**
     * validate 失败（业务规则驳回）：携带 {@link Reason} + 客户话术。
     * <b>≠ DegradationScenario</b>——业务驳回≠系统失败（E1）：不 ShortCircuit，走 presetReply 短路。
     */
    record Rejected(Reason reason, String customerMessage) implements AfterSaleWorkflowOutcome {
    }

    /** 审批恒驳回至 maxIterations 强制终止（护栏兜底，{@link AfterSaleWorkflowGraph#MAX_ITERATIONS}）。 */
    record Denied(String reason) implements AfterSaleWorkflowOutcome {
    }

    /** 审批超时未决议（submit 已跑、售后已提交但未获批准，不假装成功）。 */
    record Timeout() implements AfterSaleWorkflowOutcome {
    }
}
