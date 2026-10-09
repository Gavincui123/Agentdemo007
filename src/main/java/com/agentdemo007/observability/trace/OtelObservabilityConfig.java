package com.agentdemo007.observability.trace;

import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * traceId 对齐装配（Phase 23，恒开——应用契约不变式，与导出/传播开关解耦）。
 *
 * <p>职责边界：Boot starter（spring-boot-starter-opentelemetry）已自动装配 OTel SDK 全家
 * （SdkTracerProvider / Sampler / BatchSpanProcessor / OTLP 导出 / W3C 传播 / MVC server span），
 * 本配置只补 starter 无法替应用决定的一件事——<b>traceId 对齐</b>：SDK 默认随机生成 traceId，
 * 与应用 traceId（MDC / X-Trace-Id 响应头）是两套值；经
 * {@code SdkTracerProviderBuilderCustomizer} 换上 {@link MdcBackedIdGenerator} 后，
 * 无父 span 沿用 MDC traceId，用户凭响应头 traceId 即可在 Jaeger 检索全链路。
 *
 * <p>为什么恒开：micrometer 桥接的 Slf4JEventListener 无条件随 starter 生效——观察范围打开时
 * 它把 server span 的 traceId 写进 MDC（键同为 traceId）。若对齐不恒开，未开导出时 server span
 * 是随机 id，观察范围内 MDC 会被改写成与 X-Trace-Id 头不同的值（header/body 双真值源打架）。
 * 对齐恒开后三处同源（响应头 / UnifiedResponse / logback），开关 {@code app.otel.enabled}
 * 只管 {@link OtelTraceContextPropagator}（MQ W3C 传播实现切换）。
 */
@Configuration
public class OtelObservabilityConfig {

    @Bean
    public SdkTracerProviderBuilderCustomizer mdcBackedIdGeneratorCustomizer() {
        return builder -> builder.setIdGenerator(new MdcBackedIdGenerator());
    }
}
