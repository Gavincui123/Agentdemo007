package com.agentdemo007.gateway.exception;

/**
 * 全部模型（主 + 备选）不可用（容灾耗尽）。
 *
 * <p>由 {@link com.agentdemo007.gateway.core.FailoverExecutor} 在主模型与所有备选均失败后抛出，
 * 保留最后一条 cause；上层收口为 LLM_DOWN 降级话术继续推进或短路（视策略）。
 */
public class LlmUnavailableException extends LlmGatewayException {

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
