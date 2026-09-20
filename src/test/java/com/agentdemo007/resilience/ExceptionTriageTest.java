package com.agentdemo007.resilience;

import com.agentdemo007.gateway.exception.CircuitOpenException;
import com.agentdemo007.gateway.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常分诊器测试（Phase 5·韧性层·四层异常分诊 §5.8）。
 *
 * <p>验证 Throwable → TriageResult 映射：提供方瞬态异常(429/5xx/超时)→重试；
 * 不可重试客户端异常(401/403/404)→立即失败；工具可恢复→反馈 LLM；致命(注入/权限)→审计失败。
 * 另：我方预算超限 {@link RateLimitExceededException} 不重试（重试同模型无意义，需等窗口重置）。
 */
class ExceptionTriageTest {

    private final ExceptionTriage triage = new ExceptionTriage();

    @Test
    void transientException_isRetryable() {
        TriageResult r = triage.triage(new TransientException("429 Too Many Requests"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.RETRYABLE_TRANSIENT);
        assertThat(r.decision()).isEqualTo(Decision.RETRY);
        assertThat(r.retryAfterMs()).isLessThan(0); // 未指定 Retry-After
    }

    @Test
    void transientException_honoursRetryAfterHint() {
        TriageResult r = triage.triage(new TransientException("429", 5000L));

        assertThat(r.retryAfterMs()).isEqualTo(5000L);
    }

    @Test
    void nonRetryable_failsImmediately() {
        TriageResult r = triage.triage(new NonRetryableException("403 Forbidden"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.NON_RETRYABLE_CLIENT);
        assertThat(r.decision()).isEqualTo(Decision.FAIL);
    }

    @Test
    void toolRecoverable_feedbacksToLlm() {
        TriageResult r = triage.triage(new ToolRecoverableException("参数类型不匹配"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.TOOL_RECOVERABLE);
        assertThat(r.decision()).isEqualTo(Decision.FEEDBACK_TO_LLM);
    }

    @Test
    void fatal_auditsAndFails() {
        TriageResult r = triage.triage(new FatalException("检测到提示注入"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.FATAL);
        assertThat(r.decision()).isEqualTo(Decision.AUDIT_AND_FAIL);
    }

    @Test
    void ownBudgetLimit_notRetried() {
        TriageResult r = triage.triage(new RateLimitExceededException("日 Token 预算超限"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.NON_RETRYABLE_CLIENT);
        assertThat(r.decision()).isEqualTo(Decision.FAIL);
    }

    @Test
    void circuitOpen_failsImmediatelyAndFailovers() {
        // 模型熔断开路：不重试同模型（ResilientExecutor 即抛），交 FailoverExecutor 切备选
        TriageResult r = triage.triage(new CircuitOpenException("siliconflow-large"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.NON_RETRYABLE_CLIENT);
        assertThat(r.decision()).isEqualTo(Decision.FAIL);
    }

    @Test
    void unknownTransient_isOptimisticallyRetried() {
        // 未知运行时异常按瞬态乐观重试（网络抖动等常见），熔断器兜底系统性故障
        TriageResult r = triage.triage(new RuntimeException("connection reset"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.RETRYABLE_TRANSIENT);
        assertThat(r.decision()).isEqualTo(Decision.RETRY);
    }

    // ---- 工具异常分诊（Phase 9 工具韧性·2026-09-17 扩展）----

    @Test
    void toolHttp5xx_isRetryable() {
        assertThat(triage.triage(new ToolHttpException(500, "订单系统内部错误")).category())
                .isEqualTo(ExceptionCategory.RETRYABLE_TRANSIENT);
        assertThat(triage.triage(new ToolHttpException(503, "服务不可用")).decision())
                .isEqualTo(Decision.RETRY);
    }

    @Test
    void toolHttp408And429_areRetryable() {
        assertThat(triage.triage(new ToolHttpException(408, "请求超时")).category())
                .isEqualTo(ExceptionCategory.RETRYABLE_TRANSIENT);
        assertThat(triage.triage(new ToolHttpException(429, "外部系统限流")).category())
                .isEqualTo(ExceptionCategory.RETRYABLE_TRANSIENT);
    }

    @Test
    void toolHttp4xx_failsImmediately() {
        // 4xx 明确拒绝（鉴权/参数/不存在）：重试同参数无意义 → 交错误回喂 LLM
        TriageResult r = triage.triage(new ToolHttpException(400, "订单号不存在"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.NON_RETRYABLE_CLIENT);
        assertThat(r.decision()).isEqualTo(Decision.FAIL);
    }

    @Test
    void toolTimeout_isRetryable() {
        TriageResult r = triage.triage(new ToolTimeoutException("工具执行超时（10000ms）"));

        assertThat(r.category()).isEqualTo(ExceptionCategory.RETRYABLE_TRANSIENT);
        assertThat(r.decision()).isEqualTo(Decision.RETRY);
    }
}
