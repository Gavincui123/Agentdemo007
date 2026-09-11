package com.agentdemo007.resilience;

/**
 * 异常处置决策（Phase 5·韧性层）。
 *
 * <p>与 {@link ExceptionCategory} 一一对应：分诊结论决定下游动作——
 * 重试退避 / 立即失败 / 反馈 LLM 自纠正 / 审计后失败（不提交 LLM）。
 */
public enum Decision {
    /** 指数退避 + 全抖动透明重试（同模型），耗尽触发故障转移。 */
    RETRY,
    /** 立即失败，不重试（仍可由 FailoverExecutor 切换备选模型）。 */
    FAIL,
    /** 完整异常内容封装反馈 LLM 自纠正（受最大迭代次数限制）。 */
    FEEDBACK_TO_LLM,
    /** 立即失败 + 审计留痕，绝不提交 LLM（注入/权限/非法状态）。 */
    AUDIT_AND_FAIL
}
