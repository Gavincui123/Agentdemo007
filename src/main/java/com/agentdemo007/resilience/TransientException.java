package com.agentdemo007.resilience;

/**
 * 提供方瞬态异常（429 限流 / 5xx / 网络超时 / 连接失败）——可退避重试。
 *
 * <p>携带可选 {@code retryAfterMs}：当提供方返回 {@code Retry-After} 头时优先遵循（§5.8），
 * 否则为 -1，由 {@link BackoffStrategy} 计算指数退避。
 */
public class TransientException extends ResilienceException {

    private final long retryAfterMs;

    public TransientException(String message) {
        this(message, null, -1L);
    }

    public TransientException(String message, long retryAfterMs) {
        this(message, null, retryAfterMs);
    }

    public TransientException(String message, Throwable cause, long retryAfterMs) {
        super(message, cause);
        this.retryAfterMs = retryAfterMs;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
