package com.agentdemo007.observability.trace;

import com.agentdemo007.common.trace.TraceId;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * OTel W3C 真实现的 trace 上下文传播（Phase 23，{@code app.otel.enabled=true} 时
 * 以 {@code @Primary} 覆盖 {@link MdcTraceContextPropagator}——即
 * {@link TraceContextPropagator} javadoc 预留的「换实现不换收口」路径落地）。
 *
 * <p>与 MDC 桥接实现的差别：注入走 OTel {@code TextMapPropagator}（当前上下文里活跃的
 * span——含 Boot 自动装配的 HTTP server span），而非仅凭 MDC traceId 合成，重试/转移后的
 * 子 span 归属天然正确；恢复时除写 MDC（日志信道对齐，不变）外还把提取到的上下文
 * {@code makeCurrent}，使消费线程上手动开出的 child span（经 {@code AgentTracer}）归属
 * 生产侧同一 trace——跨进程瀑布图在 Jaeger 中连成整棵树。
 *
 * <p>W3C 传播器清单由 Boot 装配（默认 produce W3C / consume W3C+B3），本类不感知具体实现。
 * ②每步降级：所有方法内部兜底，null carrier / 非法头 / OTel 异常均不抛、不影响消息投递/消费。
 */
@Primary
@Component
@ConditionalOnProperty(name = "app.otel.enabled", havingValue = "true")
public class OtelTraceContextPropagator implements TraceContextPropagator {

    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return (carrier == null) ? null : carrier.get(key);
        }
    };

    private final OpenTelemetry openTelemetry;

    public OtelTraceContextPropagator(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void inject(Map<String, String> carrier) {
        if (carrier == null) {
            return;
        }
        try {
            openTelemetry.getPropagators().getTextMapPropagator()
                    .inject(Context.current(), carrier, SETTER);
        } catch (Exception ignored) {
            // ② best-effort：永不抛，不影响投递
        }
    }

    @Override
    public String extractTraceId(Map<String, String> carrier) {
        try {
            Span remote = Span.fromContextOrNull(extract(carrier));
            return (remote != null && remote.getSpanContext().isValid())
                    ? remote.getSpanContext().getTraceId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public AutoCloseable restoreScope(Map<String, String> carrier) {
        try {
            Context extracted = extract(carrier);
            Span remote = Span.fromContextOrNull(extracted);
            if (remote == null || !remote.getSpanContext().isValid()) {
                return () -> {
                };
            }
            String previous = MDC.get(TraceId.MDC_KEY);
            MDC.put(TraceId.MDC_KEY, remote.getSpanContext().getTraceId()); // 日志信道：消费线程 traceId 与生产侧一致（既有契约）
            Scope scope = extracted.makeCurrent();     // 链路信道：手动 child span 归属此 trace
            return () -> {
                scope.close();
                if (previous != null) {
                    MDC.put(TraceId.MDC_KEY, previous);
                } else {
                    MDC.remove(TraceId.MDC_KEY);
                }
            };
        } catch (Exception e) {
            return () -> {
            };
        }
    }

    /** W3C 提取（在当前上下文基础上解析 carrier，缺失 traceparent 时原样返回）。 */
    private Context extract(Map<String, String> carrier) {
        return openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), carrier, GETTER);
    }
}
