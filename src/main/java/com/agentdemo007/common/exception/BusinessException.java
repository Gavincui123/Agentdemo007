package com.agentdemo007.common.exception;

import com.agentdemo007.common.response.ErrorCode;

/**
 * 业务异常：携带 {@link ErrorCode}，由全局异常处理器统一兜底为
 * {@link com.agentdemo007.common.response.UnifiedResponse}，HTTP 200。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
