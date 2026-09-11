package com.agentdemo007.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路标识收口：traceId 的唯一解析入口（第四原则·防漂移）。
 *
 * <p>统一从 MDC 取 {@code traceId}（由 {@code TraceFilter} 写入），缺失时兜底生成无横线 UUID。
 * 所有需要 traceId 的组件（{@link com.agentdemo007.common.response.UnifiedResponse}、
 * {@link com.agentdemo007.common.pipeline.PipelineContext} 等）一律经此收口，禁止各自重复实现导致不一致。
 */
public final class TraceId {

    public static final String MDC_KEY = "traceId";

    private TraceId() {
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString().replace("-", "");
    }
}
