package com.agentdemo007.capability.workflow;

/**
 * 待续跑工作流（[[business-tools-workflow-dag]] §2.5·多轮澄清续跑）。
 *
 * <p>Turn 1 用户发起高风险售后（refund/return）但未带订单号 → {@link WorkflowExecutionStep} entity-gate
 * 澄清话术短路 + 将意图存入 {@link PendingWorkflowStore}（按 sessionId）；Turn 2 用户补订单号 →
 * {@link WorkflowExecutionStep} 状态机按当前轮 routePlan 决策续跑/切换/放弃（pending 提示经
 * RoutePlanStep 注入 route prompt）。仅携 intent（routePlan 其余字段由 RoutePlanStep 重新产出——
 * requiresWorkflow=true 即可触发图）。
 *
 * @param intent 售后意图（return_request / refund_request），供续跑步恢复路由触发正确图。
 */
public record PendingWorkflow(String intent) {
}
