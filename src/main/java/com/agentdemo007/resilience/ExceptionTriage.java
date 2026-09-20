package com.agentdemo007.resilience;

import com.agentdemo007.gateway.exception.CircuitOpenException;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import com.agentdemo007.gateway.exception.RateLimitExceededException;

/**
 * 异常分诊器（Phase 5·韧性层·四层异常分诊 §5.8）。
 *
 * <p>把 Throwable 映射为 {@link TriageResult}（类别 + 决策 + Retry-After 提示）。
 * 识别顺序遵循"最致命/最特定优先"原则：
 * <ol>
 *   <li>{@link FatalException} → FATAL（审计失败，不提交 LLM）。</li>
 *   <li>{@link ToolRecoverableException} → TOOL_RECOVERABLE（反馈 LLM 自纠正）。</li>
 *   <li>{@link NonRetryableException} → NON_RETRYABLE_CLIENT（立即失败）。</li>
 *   <li>{@link ToolHttpException} → 5xx/408/429 → RETRYABLE_TRANSIENT（退避重试）；
 *       其余 4xx → NON_RETRYABLE_CLIENT（外部系统明确拒绝，重试同参数无意义，反馈 LLM）。</li>
 *   <li>{@link ToolTimeoutException} → RETRYABLE_TRANSIENT（工具执行超时，退避重试）。</li>
 *   <li>{@link TransientException} → RETRYABLE_TRANSIENT（退避重试，遵循其 Retry-After 提示）。</li>
 *   <li>{@link RateLimitExceededException} → NON_RETRYABLE_CLIENT（我方预算超限，重试同模型无意义）。</li>
 *   <li>{@link ModelSelectionException}/{@link LlmUnavailableException} → NON_RETRYABLE_CLIENT。</li>
 *   <li>未知 RuntimeException → RETRYABLE_TRANSIENT（乐观重试，熔断器兜底系统性故障）。</li>
 * </ol>
 *
 * <p>关键区分：提供方 429（{@link TransientException}，可重试）与我方预算超限
 * （{@link RateLimitExceededException}，不重试）——前者重试有用，后者需等窗口重置。
 */
public class ExceptionTriage {

    public TriageResult triage(Throwable t) {
        if (t == null) {
            return TriageResult.of(ExceptionCategory.FATAL, -1L, "空异常");
        }
        if (t instanceof FatalException) {
            return TriageResult.of(ExceptionCategory.FATAL, -1L, "致命：注入/权限/非法状态");
        }
        if (t instanceof ToolRecoverableException) {
            return TriageResult.of(ExceptionCategory.TOOL_RECOVERABLE, -1L, "工具可恢复：反馈 LLM 自纠正");
        }
        if (t instanceof NonRetryableException) {
            return TriageResult.of(ExceptionCategory.NON_RETRYABLE_CLIENT, -1L, "不可重试客户端错误");
        }
        if (t instanceof ToolHttpException h) {
            if (h.status() >= 500 || h.status() == 408 || h.status() == 429) {
                return TriageResult.of(ExceptionCategory.RETRYABLE_TRANSIENT, -1L,
                        "服务端瞬态错误（HTTP " + h.status() + "）：退避重试");
            }
            return TriageResult.of(ExceptionCategory.NON_RETRYABLE_CLIENT, -1L,
                    "客户端错误（HTTP " + h.status() + "）：重试同参数无意义，反馈 LLM");
        }
        if (t instanceof ToolTimeoutException) {
            return TriageResult.of(ExceptionCategory.RETRYABLE_TRANSIENT, -1L, "工具执行超时：退避重试");
        }
        if (t instanceof TransientException te) {
            return TriageResult.of(ExceptionCategory.RETRYABLE_TRANSIENT, te.retryAfterMs(), "瞬态可重试");
        }
        if (t instanceof RateLimitExceededException) {
            return TriageResult.of(ExceptionCategory.NON_RETRYABLE_CLIENT, -1L, "我方预算超限：不重试");
        }
        if (t instanceof ModelSelectionException) {
            return TriageResult.of(ExceptionCategory.NON_RETRYABLE_CLIENT, -1L, "无可用模型");
        }
        if (t instanceof LlmUnavailableException) {
            return TriageResult.of(ExceptionCategory.NON_RETRYABLE_CLIENT, -1L, "故障转移耗尽");
        }
        if (t instanceof CircuitOpenException) {
            return TriageResult.of(ExceptionCategory.NON_RETRYABLE_CLIENT, -1L, "模型熔断开路：切备选");
        }
        return TriageResult.of(ExceptionCategory.RETRYABLE_TRANSIENT, -1L, "未知异常乐观重试");
    }
}
