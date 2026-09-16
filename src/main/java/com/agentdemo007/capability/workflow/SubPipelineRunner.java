package com.agentdemo007.capability.workflow;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.session.model.ChatMessage;

import java.util.List;

/**
 * 子管线 seam（[[p0-intent-switch-clarify-design]] §7.3）：并发第二腿复用能力段步骤产出组装 prompt。
 *
 * <p>由 {@link WorkflowExecutionStep} 的并发分支（Task 14）持有，腿2 跑 capability 段
 * （Tool@650 → RAG@660 → ContextBuilder@700）产出 assembledPrompt 供 OutputStep 合并。
 * 实现见 {@code CapabilitySegmentRunner}（Task 13）；本接口先落地为编译 seam（Task 9 构造器引用）。
 */
public interface SubPipelineRunner {

    /**
     * 跑 capability 段（Tool@650 → RAG@660 → ContextBuilder@700），返 assembledPrompt；
     * 任一步异常→null（腿2 降级，OutputStep 优雅兜底，§7.4）。
     */
    List<ChatMessage> runCapabilitySegment(PipelineContext subContext);
}
