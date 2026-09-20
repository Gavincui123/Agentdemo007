package com.agentdemo007.resilience;

/**
 * 异常分诊结论（Phase 5·韧性层）。
 *
 * <p>{@link ExceptionTriage#triage(Throwable)} 的产出：类别 + 决策 + 可选 Retry-After 提示 + 备注。
 * {@code retryAfterMs < 0} 表示未指定（由退避策略自行计算）。
 */
public record TriageResult(ExceptionCategory category, Decision decision, long retryAfterMs, String note) {

    /**
     * 便捷工厂：决策取类别默认，备注 + Retry-After 由调用方提供。
     * public：工具扩展分诊器（{@code capability.tool} 包的 {@code ToolExceptionTriage}）复用同一产出契约。
     */
    public static TriageResult of(ExceptionCategory category, long retryAfterMs, String note) {
        return new TriageResult(category, category.decision(), retryAfterMs, note);
    }

    public boolean retryable() {
        return decision == Decision.RETRY;
    }
}
