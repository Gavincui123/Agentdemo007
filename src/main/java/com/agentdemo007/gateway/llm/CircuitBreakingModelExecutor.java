package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.exception.CircuitOpenException;
import com.agentdemo007.resilience.Decision;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.ModelCircuitBreaker;
import com.agentdemo007.resilience.TriageResult;

/**
 * 熔断装饰执行器（主备容灾·模型级断路器接线层）。
 *
 * <p>包任意 delegate（装配时为 {@link RoutingModelExecutor}），在每次 execute 前查
 * {@link ModelCircuitBreaker#allow}：OPEN→抛 {@link CircuitOpenException}（不调 delegate，快速失败）；
 * 放行则调 delegate，成功记 {@link ModelCircuitBreaker#recordSuccess}，失败经 {@link ExceptionTriage}
 * 分诊后——<b>仅模型可用性失败</b>（瞬态/不可重试客户端错）计 {@link ModelCircuitBreaker#recordFailure}，
 * {@link Decision#AUDIT_AND_FAIL}（注入/权限致命）与 {@link Decision#FEEDBACK_TO_LLM}（工具可恢复）
 * <b>不计入</b>（非模型健康问题，误计会错熔断）。
 *
 * <p>开闭原则：{@link com.agentdemo007.gateway.core.FailoverExecutor} 不动——熔断作为外部装饰层接入，
 * FailoverExecutor 见 {@link CircuitOpenException}→分诊 NON_RETRYABLE_CLIENT→切备选（与既有容灾通道同路）。
 * 熔断位于重试循环<b>内</b>：同模型每次失败调用计 1（含 {@code ResilientExecutor} 的同模型退避重试），
 * 阈值偏松（默认 5/60s）避免误熔断。
 */
public class CircuitBreakingModelExecutor implements ModelExecutor {

    private final ModelExecutor delegate;
    private final ModelCircuitBreaker breaker;
    private final ExceptionTriage triage;

    public CircuitBreakingModelExecutor(ModelExecutor delegate, ModelCircuitBreaker breaker, ExceptionTriage triage) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.triage = triage;
    }

    @Override
    public LlmResponse execute(LlmRequest request) {
        String modelId = request.modelId();
        if (!breaker.allow(modelId)) {
            throw new CircuitOpenException(modelId);
        }
        try {
            LlmResponse response = delegate.execute(request);
            breaker.recordSuccess(modelId);
            return response;
        } catch (RuntimeException e) {
            TriageResult result = triage.triage(e);
            Decision d = result.decision();
            // 致命(注入/权限)/工具可恢复 非模型可用性问题，不计熔断
            if (d != Decision.AUDIT_AND_FAIL && d != Decision.FEEDBACK_TO_LLM) {
                breaker.recordFailure(modelId);
            }
            throw e;
        }
    }
}
