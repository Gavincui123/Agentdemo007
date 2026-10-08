package com.agentdemo007.observability.trace;

import com.agentdemo007.observability.AgentTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.Map;

/**
 * 出站 HTTP 客户端链路拦截器（Phase 23）。
 *
 * <p>动机：项目里 Chroma / embedding / rerank 三条出站通道是手建 {@code RestTemplate}
 * （{@code new RestTemplate(factory)}，不经自动装配 builder），Boot 4.1.1（Framework 7）
 * 已移除 {@code RestTemplateBuilder}——3.x 的「换 builder 即得自动 client span」路径不存在，
 * 故按本项目 seam 哲学手写拦截器收口：出站请求开 CLIENT span（落 method/url/状态码/异常）+
 * W3C traceparent 注入（下游可续链），复用 {@link AgentTracer} 与 OTel 传播器（与 MQ 传播
 * 同一套 W3C 口径）。装配：三个 RestTemplate bean 各 {@code getInterceptors().add(...)} 一行。
 *
 * <p>②每步降级：拦截器任何异常不吞不包——span 照常收口后原样上抛 IO 异常（重试/熔断
 * 语义归既有 CircuitBreakerGuard 层，本拦截器不干预业务行为）。
 */
public final class TraceClientInterceptor implements ClientHttpRequestInterceptor {

    private static final TextMapSetter<HttpRequest> SETTER = (req, key, value) -> req.getHeaders().add(key, value);

    private final AgentTracer tracer;
    private final OpenTelemetry openTelemetry;

    public TraceClientInterceptor(AgentTracer tracer, OpenTelemetry openTelemetry) {
        this.tracer = tracer;
        this.openTelemetry = openTelemetry;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String uri = request.getURI().toString();
        AgentTracer.SpanHandle span = tracer.startSpan("HTTP " + request.getMethod(), SpanKind.CLIENT,
                "http.request.method", request.getMethod().name(),
                "server.address", request.getURI().getHost(),
                "url.full", uri);
        try {
            openTelemetry.getPropagators().getTextMapPropagator()
                    .inject(Context.current(), request, SETTER);
            ClientHttpResponse response = execution.execute(request, body);
            span.attr("http.response.status_code", response.getStatusCode().value());
            return response;
        } catch (IOException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
