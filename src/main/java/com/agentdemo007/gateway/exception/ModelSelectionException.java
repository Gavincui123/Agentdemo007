package com.agentdemo007.gateway.exception;

/**
 * 模型选择异常（无可用候选 / 无匹配标签）。
 *
 * <p>由 {@code ModelSelector} 在空候选集或标签无匹配时抛出，
 * 网关层捕获后走 {@code FailoverExecutor} 故障转移或收口为降级话术。
 */
public class ModelSelectionException extends RuntimeException {

    public ModelSelectionException(String message) {
        super(message);
    }
}
