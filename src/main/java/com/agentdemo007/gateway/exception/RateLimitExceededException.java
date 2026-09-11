package com.agentdemo007.gateway.exception;

/**
 * 速率/Token 预算超限（§5.12 + eval deg-004）。
 *
 * <p>由 {@link com.agentdemo007.gateway.core.TokenBudgetChecker} 在 {@code check} 阶段抛出，
 * 网关据此零 LLM 短路；上层收口为 RATE_LIMITED 话术（HTTP 200），不返回 429 技术码。
 */
public class RateLimitExceededException extends LlmGatewayException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
