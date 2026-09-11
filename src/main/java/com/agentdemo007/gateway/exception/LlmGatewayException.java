package com.agentdemo007.gateway.exception;

/**
 * 网关异常基类（第六层·出站调用失败收口）。
 *
 * <p>由上层"模型网关步骤"捕获：{@link RateLimitExceededException}→话术短路(RATE_LIMITED)；
 * {@link LlmUnavailableException}→降级(LLM_DOWN)。不向用户抛 5xx 技术码。
 */
public class LlmGatewayException extends RuntimeException {

    public LlmGatewayException(String message) {
        super(message);
    }

    public LlmGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
