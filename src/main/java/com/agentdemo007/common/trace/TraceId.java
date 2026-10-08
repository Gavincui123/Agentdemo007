package com.agentdemo007.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路标识收口：traceId 的唯一解析入口（第四原则·防漂移）。
 *
 * <p>统一从 MDC 取 {@code traceId}（由 {@code TraceFilter} 写入），缺失时兜底生成无横线 UUID。
 * 所有需要 traceId 的组件（{@link com.agentdemo007.common.response.UnifiedResponse}、
 * {@link com.agentdemo007.common.pipeline.PipelineContext} 等）一律经此收口，禁止各自重复实现导致不一致。
 *
 * <p>Phase 23 OTel 对齐：W3C traceparent 要求 traceId 为 32 位小写 hex，OTel 的
 * {@code MdcBackedIdGenerator} 会以 MDC 值直接充当 OTel traceId——因此入站值必须先经
 * {@link #normalize(String)} 校验归一（非法即弃），否则非法 traceId 混入会破坏 W3C 格式、
 * Jaeger 检索不到。本类同时是合法格式的唯一判定口径（TraceFilter / IdGenerator 共用）。
 */
public final class TraceId {

    public static final String MDC_KEY = "traceId";

    private static final int TRACE_ID_LENGTH = 32;

    private TraceId() {
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * traceId 合法性判定（traceId 收口）：恰 32 位 hex（大小写均可）。
     * W3C Trace Context 要求 32 位小写 hex；无横线 UUID（{@link #current()} 兜底口径）天然满足。
     */
    public static boolean isValid(String id) {
        return normalize(id) != null;
    }

    /**
     * 归一化：trim + 转小写 + 32 位 hex 校验；非法返回 null（调用方据此走重新生成兜底）。
     * 上游可能传大写或带空白（人工 curl 调试常见），统一归一后进 MDC。
     */
    public static String normalize(String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim().toLowerCase();
        if (trimmed.length() != TRACE_ID_LENGTH) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
        }
        return trimmed;
    }
}
