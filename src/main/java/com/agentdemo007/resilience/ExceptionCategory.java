package com.agentdemo007.resilience;

/**
 * 异常四层分诊类别（Phase 5·韧性层，对齐 §5.8）。
 *
 * <p>每个类别绑定默认 {@link Decision}，{@link ExceptionTriage} 据此决定重试/失败/反馈/审计。
 *
 * <table>
 *   <tr><th>类别</th><th>场景</th><th>处置</th></tr>
 *   <tr><td>RETRYABLE_TRANSIENT</td><td>429 限流、5xx、网络超时、连接失败</td><td>退避重试，耗尽→故障转移</td></tr>
 *   <tr><td>NON_RETRYABLE_CLIENT</td><td>401/403/404、参数校验失败</td><td>立即失败</td></tr>
 *   <tr><td>TOOL_RECOVERABLE</td><td>工具参数错误、非法表达式、除零</td><td>反馈 LLM 自纠正</td></tr>
 *   <tr><td>FATAL</td><td>提示注入、权限不足、非法状态</td><td>审计失败，不提交 LLM</td></tr>
 * </table>
 */
public enum ExceptionCategory {

    RETRYABLE_TRANSIENT(Decision.RETRY),
    NON_RETRYABLE_CLIENT(Decision.FAIL),
    TOOL_RECOVERABLE(Decision.FEEDBACK_TO_LLM),
    FATAL(Decision.AUDIT_AND_FAIL);

    private final Decision decision;

    ExceptionCategory(Decision decision) {
        this.decision = decision;
    }

    public Decision decision() {
        return decision;
    }
}
