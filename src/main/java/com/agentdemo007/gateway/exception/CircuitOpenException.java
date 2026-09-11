package com.agentdemo007.gateway.exception;

/**
 * 模型熔断开路异常（主备容灾·模型级断路器信号）。
 *
 * <p>由 {@code CircuitBreakingModelExecutor} 在 {@code ModelCircuitBreaker.allow(modelId)} 返回 false
 * 时抛出——表示该模型当前熔断开路（窗口内失败数达阈值、冷却未过），不应发起调用。
 * 经 {@link com.agentdemo007.resilience.ExceptionTriage} 分诊为 {@code NON_RETRYABLE_CLIENT}：
 * ResilientExecutor 不重试同模型（即抛），FailoverExecutor 据此切备选模型——
 * 与"故障转移耗尽"同走容灾通道，不阻塞、不暴露技术码。
 *
 * <p>继承 {@link LlmGatewayException}（与 {@link LlmUnavailableException} 同族），由上层网关步骤
 * 收口为 MODEL_DOWN 话术（仅当主备全熔断/全不可用时）。
 */
public class CircuitOpenException extends LlmGatewayException {

    private final String modelId;

    public CircuitOpenException(String modelId) {
        super("模型熔断开路：modelId=" + modelId);
        this.modelId = modelId;
    }

    public String modelId() {
        return modelId;
    }
}
