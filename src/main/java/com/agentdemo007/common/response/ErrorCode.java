package com.agentdemo007.common.response;

/**
 * 统一错误码枚举。
 * {@code code} 为业务码（与 HTTP 状态解耦），{@code message} 为面向用户的脱敏提示。
 */
public enum ErrorCode {

    SUCCESS(0, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    RATE_LIMITED(429, "请求过于频繁"),
    SESSION_CACHE_ERROR(5030, "会话缓存异常"),
    INTERNAL_ERROR(5000, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
