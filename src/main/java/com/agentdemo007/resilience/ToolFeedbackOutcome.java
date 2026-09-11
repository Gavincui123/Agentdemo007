package com.agentdemo007.resilience;

/**
 * 工具错误反馈结果（收口 sealed，呼应 {@link com.agentdemo007.common.pipeline.StepOutcome} 的收口风格）。
 *
 * <p>{@link Resubmit}：在最大迭代内，携带反馈 prompt 交 LLM 自纠正（重提交工具调用）。
 * {@link Exhausted}：达到/超过最大迭代，携带原异常交上层降级收口（不再反馈）。
 */
public sealed interface ToolFeedbackOutcome permits ToolFeedbackOutcome.Resubmit, ToolFeedbackOutcome.Exhausted {

    /** 继续反馈：携带反馈 prompt 交 LLM 自纠正。 */
    record Resubmit(String prompt) implements ToolFeedbackOutcome {}

    /** 迭代耗尽：交上层降级收口，不再反馈 LLM。 */
    record Exhausted(Throwable cause) implements ToolFeedbackOutcome {}
}
