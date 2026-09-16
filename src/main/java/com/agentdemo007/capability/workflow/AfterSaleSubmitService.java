package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;

/**
 * 售后动作提交 seam（[[business-tools-workflow-dag]] §2.3·D1 参数化图：return/refund 共用此 seam）。
 *
 * <p>functional interface——{@code submit_approval} 节点委托此，返回售后请求 id（写入 {@code context.workflowResult}，
 * 供 approval_gate {@link WorkflowApprovalDecision#await}）。return/refund 流各注入自身 impl（退货/退款后端）；
 * dev/单测用 lambda 返固定 id。真后端接入迭代后补（同 {@link RefundService} NO_OP 惯例）。
 */
@FunctionalInterface
public interface AfterSaleSubmitService {

    /** 提交售后动作，返回请求 id/状态（写入 workflowResult，供审批门 await）。 */
    String submit(PipelineContext context);
}
