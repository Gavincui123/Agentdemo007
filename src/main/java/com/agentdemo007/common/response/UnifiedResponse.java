package com.agentdemo007.common.response;

import com.agentdemo007.common.trace.TraceId;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一返回体 {@code {code, message, traceId, timestamp, data}}（第四原则·对外收口）。
 *
 * <p>{@code traceId} 经 {@link TraceId#current()} 收口解析（由 {@code TraceFilter} 写入 MDC，缺失兜底生成），
 * 保证全链路可追溯。{@code timestamp} 为 ISO-8601 偏移时间。
 *
 * <p>使用 record 保证序列化字段固定，不会泄漏堆栈等内部信息。
 * 流水线终端 {@code PipelineResult} 由 {@code ChatController} 翻译为本类型对外输出。
 */
public record UnifiedResponse(int code, String message, String traceId, String timestamp, Object data) {

    public static UnifiedResponse success(Object data) {
        return new UnifiedResponse(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), TraceId.current(), now(), data);
    }

    public static UnifiedResponse error(ErrorCode errorCode) {
        return new UnifiedResponse(errorCode.code(), errorCode.message(), TraceId.current(), now(), null);
    }

    public static UnifiedResponse error(ErrorCode errorCode, String message) {
        return new UnifiedResponse(errorCode.code(), message, TraceId.current(), now(), null);
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
