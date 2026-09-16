package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;

/**
 * 售后固定工作流 seam（[[business-tools-workflow-dag]] §2.4·统一收口）。
 *
 * <p>单方法：驱动子图同步阻塞至 END，返回 {@link AfterSaleWorkflowOutcome} 终态。{@link AfterSaleWorkflowGraph}
 * 实现此 seam；{@link WorkflowExecutionStep} 依赖此 seam（非具体图类）——便于：
 * <ul>
 *   <li>return/refund 流各注入一个参数化 {@link AfterSaleWorkflowGraph}（D1：政策域+校验规则+提交服务参数化），
 *       本步按 {@code rp.intent()} 选图（"return_request"→退货图，否则→退款图）；</li>
 *   <li>单测用 scripted lambda 返固定终态，聚焦 {@link WorkflowExecutionStep} 的收口逻辑
 *       （图内部 6 节点 DAG 由 {@link AfterSaleWorkflowGraphTest} 覆盖，不在此重跑）。</li>
 * </ul>
 *
 * <p>语义镜像既有 seams（{@link AfterSaleSubmitService}/{@link WorkflowApprovalDecision}）：
 * 图<b>不直接产 {@code PipelineResult}</b>，写 context 强类型字段（{@code workflowResult}/presetReply），
 * 终态由顶层 {@code PipelineExecutor} 收口（④统一收口——换子图不换出口）。
 */
@FunctionalInterface
public interface AfterSaleWorkflow {

    /** 驱动售后固定工作流子图，返回终态（Approved/Rejected/Denied/Timeout）。异常由实现包 {@link IllegalStateException}。 */
    AfterSaleWorkflowOutcome invoke(PipelineContext context);
}
