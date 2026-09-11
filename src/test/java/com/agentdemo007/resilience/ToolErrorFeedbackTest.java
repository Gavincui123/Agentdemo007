package com.agentdemo007.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具错误反馈测试（Phase 5·韧性层 §5.8 TOOL_RECOVERABLE）。
 *
 * <p>工具参数错误等可恢复异常：在最大迭代次数内，把完整异常内容封装为反馈 prompt 交 LLM 自纠正；
 * 达到/超过最大迭代 → 返回 {@link ToolFeedbackOutcome.Exhausted}（交上层降级收口，不再反馈）。
 *
 * <p>实际自纠正循环（重提交→LLM→修正工具调用→成功）属后续 Agent 编排层；
 * 本组件只负责"是否继续反馈 + 反馈 prompt 构建 + 最大迭代守卫"这一韧性层职责。
 */
class ToolErrorFeedbackTest {

    private final ToolErrorFeedback feedback = new ToolErrorFeedback(3);

    @Test
    void withinMax_returnsResubmitWithFeedbackPrompt() {
        ToolRecoverableException e = new ToolRecoverableException("get_weather 参数 city 缺失");

        ToolFeedbackOutcome outcome = feedback.feedback(e, 0);

        assertThat(outcome).isInstanceOf(ToolFeedbackOutcome.Resubmit.class);
        // 完整异常内容反馈给 LLM
        assertThat(((ToolFeedbackOutcome.Resubmit) outcome).prompt())
                .contains("get_weather 参数 city 缺失");
    }

    @Test
    void atMax_returnsExhausted() {
        ToolRecoverableException e = new ToolRecoverableException("bad args");

        ToolFeedbackOutcome outcome = feedback.feedback(e, 3); // iteration == max

        assertThat(outcome).isInstanceOf(ToolFeedbackOutcome.Exhausted.class);
        assertThat(((ToolFeedbackOutcome.Exhausted) outcome).cause()).isSameAs(e);
    }

    @Test
    void overMax_returnsExhausted() {
        ToolRecoverableException e = new ToolRecoverableException("bad args");

        assertThat(feedback.feedback(e, 4)).isInstanceOf(ToolFeedbackOutcome.Exhausted.class);
    }
}
