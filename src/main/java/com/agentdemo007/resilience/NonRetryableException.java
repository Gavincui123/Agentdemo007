package com.agentdemo007.resilience;

/**
 * 不可重试的客户端错误（401/403/404、参数校验失败）——立即失败，不退避重试。
 *
 * <p>注：立即失败指不对同模型退避重试；FailoverExecutor 仍可切换到使用不同凭证/端点的备选模型。
 */
public class NonRetryableException extends ResilienceException {

    public NonRetryableException(String message) {
        super(message);
    }

    public NonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
