package com.agentdemo007.capability.workflow;

/**
 * 售后工作流终态（sealed·[[business-tools-workflow-dag]] §2.3·E1 决策·2026-09-20 提交制收缩）。
 *
 * <p>{@link AfterSaleWorkflowGraph#invoke} 返回此类型（从终态读 {@code OUTCOME_KEY}）。
 * <b>原则（用户裁决）：审批是事件、Agent 最小权限</b>——请求内只有两种可达终态：
 * <ul>
 *   <li>{@link Rejected}——validate 失败（订单非本人/不存在/Agent 资格裁决驳回），业务规则驳回，
 *       携带 {@link Reason} + 客户话术；<b>≠ {@link com.agentdemo007.common.degradation.DegradationScenario}</b>
 *       （业务驳回≠系统失败，E1：{@code WorkflowExecutionStep} 写 {@code presetReply}+Proceed）；</li>
 *   <li>{@link Pending}——工单已提交人工审批（建单/复用即返回，无请求内等待）。批准/驳回是管理台的
 *       工单状态事件；客户对结果的感知走工单状态查询工具，后续轮次获知。</li>
 * </ul>
 * 旧 Approved/Denied/Timeout 请求内终态随等待链退役（恒批准与超时话术不再存在）。
 */
public sealed interface AfterSaleWorkflowOutcome
        permits AfterSaleWorkflowOutcome.Rejected, AfterSaleWorkflowOutcome.Pending {

    /**
     * validate 失败（业务规则驳回）：携带 {@link Reason} + 客户话术。
     * <b>≠ DegradationScenario</b>——业务驳回≠系统失败（E1）：不 ShortCircuit，走 presetReply 短路。
     */
    record Rejected(Reason reason, String customerMessage) implements AfterSaleWorkflowOutcome {
    }

    /**
     * 工单已提交人工审批（提交制）：建单/复用 PENDING 同单即返回。
     *
     * @param alreadyApproved true=同业务键工单此前已批准（未建新单防重复业务动作，客户回"此前已通过"话术）
     */
    record Pending(boolean alreadyApproved) implements AfterSaleWorkflowOutcome {
    }
}
