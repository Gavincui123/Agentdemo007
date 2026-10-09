package com.agentdemo007.observability.trace;

import com.agentdemo007.common.trace.TraceId;
import io.opentelemetry.sdk.trace.IdGenerator;
import org.slf4j.MDC;

/**
 * MDC 回填的 OTel IdGenerator（Phase 23·traceId 对齐）。
 *
 * <p>动机：OTel SDK 默认随机生成 traceId，与应用 traceId（{@code TraceFilter} 写入 MDC、
 * 回写 {@code X-Trace-Id} 响应头、经 {@code UnifiedResponse.traceId} 透出）是两套值——
 * 用户拿着响应头里的 traceId 去 Jaeger 检索会查不到。本生成器在 SDK 新建<b>无父 span</b> 时
 * 沿用线程 MDC 中的 traceId（经 {@link TraceId#normalize(String)} 32-hex 校验），使
 * OTel traceId == 应用 traceId；有父 span（HTTP server / MQ 传播恢复）时 traceId 继承父、
 * 本生成器不参与。线程无合法 MDC（如未覆盖的散点异步路径）退回随机——宁要新 trace 也不要非法格式。
 *
 * <p>装配：{@link OtelObservabilityConfig} 经 {@code SdkTracerProviderBuilderCustomizer}
 * 注入（app.otel.enabled=true 时生效）；spanId 恒随机（应用侧无 spanId 概念，无需对齐）。
 */
public final class MdcBackedIdGenerator implements IdGenerator {

    private final IdGenerator delegate = IdGenerator.random();

    @Override
    public String generateSpanId() {
        return delegate.generateSpanId();
    }

    @Override
    public String generateTraceId() {
        String mdc = MDC.get(TraceId.MDC_KEY);
        String normalized = TraceId.normalize(mdc);
        return (normalized != null) ? normalized : delegate.generateTraceId();
    }
}
