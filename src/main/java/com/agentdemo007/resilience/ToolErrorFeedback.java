package com.agentdemo007.resilience;

/**
 * 工具错误反馈（Phase 5·韧性层 §5.8 TOOL_RECOVERABLE）。
 *
 * <p>工具执行抛 {@link ToolRecoverableException}（参数错误、非法表达式、除零等）时，把完整异常内容
 * 封装为反馈 prompt 交 LLM 自纠正其工具调用参数，受最大迭代次数限制：
 * <ul>
 *   <li>{@code iteration < maxIterations} → {@link ToolFeedbackOutcome.Resubmit}（继续反馈）；</li>
 *   <li>达到/超过 → {@link ToolFeedbackOutcome.Exhausted}（交上层降级收口，不再反馈）。</li>
 * </ul>
 *
 * <p>本组件只做"是否继续反馈 + 反馈 prompt 构建"；自纠正循环本体（重提交→LLM→修正调用→成功）
 * 属后续 Agent 编排层，迭代计数由循环注入。反馈 prompt 为 LLM 输入（非用户话术），
 * 后续可由 Nacos 提示词注册中心托管模板与版本。
 */
public class ToolErrorFeedback {

    private final int maxIterations;

    public ToolErrorFeedback(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public ToolFeedbackOutcome feedback(ToolRecoverableException e, int iteration) {
        if (iteration >= maxIterations) {
            return new ToolFeedbackOutcome.Exhausted(e);
        }
        String prompt = "工具调用失败：" + e.getMessage() + "。请检查参数并修正后重新调用该工具。";
        return new ToolFeedbackOutcome.Resubmit(prompt);
    }
}
