package com.agentdemo007.common.exception;

import com.agentdemo007.common.response.ErrorCode;

/**
 * 会话缓存异常（如 Redis 故障）。
 *
 * <p>两条出口（防不一致）：
 * <ul>
 *   <li>/chat 流水线会话步骤捕获后 → {@code StepOutcome.ShortCircuit(SESSION_DOWN)} 话术短路（HTTP 200，§5.12）。</li>
 *   <li>非流水线直连调用（防御兜底）→ {@code GlobalExceptionHandler} 映射 HTTP 503 错误信封。</li>
 * </ul>
 */
public class SessionCacheException extends RuntimeException {

    public SessionCacheException(String message) {
        super(message);
    }

    public SessionCacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public ErrorCode getErrorCode() {
        return ErrorCode.SESSION_CACHE_ERROR;
    }
}
