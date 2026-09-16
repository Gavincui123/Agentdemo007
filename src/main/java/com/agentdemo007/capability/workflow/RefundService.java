package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;

/**
 * 退款提交 seam（高风险固定工作流·[[per-intent-dag]]·{@code submit_refund} 节点委托此，非手撸退款）。
 *
 * <p>真 impl 调退款后端（建退款请求，返回请求 id/状态）；dev/单测用 NO_OP/lambda 返回固定 id。
 * 迭代后补真后端接入。query/orderId 等上下文从 {@link PipelineContext} 取（standardQuery/rawInput
 * + toolResults 已由主链填充）。
 */
public interface RefundService {

    /** 提交退款请求，返回退款请求 id/状态（写入 {@code context.workflowResult}，供审批门 await）。 */
    String submit(PipelineContext context);
}
