package com.agentdemo007.observability.trace;

import com.agentdemo007.common.trace.TraceId;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * MDC 桥接的 trace 上下文传播默认实现（Phase 15·dev/no-OTel 装配）。
 *
 * <p>{@link #inject} 以 {@link TraceId#current()}（MDC，由 {@code TraceFilter} 写入）合成 W3C
 * {@code traceparent}（{@code 00-<traceId>-<spanId>-01}）；{@link #restoreScope} 在消费线程把
 * traceparent 中的 traceId 写回 MDC，使消费侧 {@code TraceId.current()} 与生产侧一致。
 *
 * <p>引擎无关（§5.14）：本类是 trace 传播的默认 {@code @Component}；prod 加 OTel 依赖后，
 * 以 OTel {@code W3CTraceContextPropagator} 装配为 {@code @Primary} 覆盖本类——carrier 形状对齐，
 * 上层（{@code RabbitMqMessagePublisher}/{@code *Consumer}）只依赖 {@link TraceContextPropagator} seam。
 *
 * <p>②每步降级：所有方法内部兜底，null carrier / 缺失 traceparent / 非法格式均不抛、
 * 不影响消息投递/消费。
 */
@Component
public class MdcTraceContextPropagator implements TraceContextPropagator {

    @Override
    public void inject(Map<String, String> carrier) {
        if (carrier == null) {
            return;
        }
        try {
            String traceId = TraceId.current();
            carrier.put(TRACEPARENT_HEADER, "00-" + traceId + "-" + spanId() + "-01");
        } catch (Exception ignored) {
            // ② best-effort：永不抛，不影响投递
        }
    }

    @Override
    public String extractTraceId(Map<String, String> carrier) {
        if (carrier == null) {
            return null;
        }
        return parseTraceId(carrier.get(TRACEPARENT_HEADER));
    }

    @Override
    public AutoCloseable restoreScope(Map<String, String> carrier) {
        String traceId = extractTraceId(carrier);
        if (traceId == null) {
            return () -> {
            };
        }
        String previous = MDC.get(TraceId.MDC_KEY);
        MDC.put(TraceId.MDC_KEY, traceId);
        return () -> {
            if (previous != null) {
                MDC.put(TraceId.MDC_KEY, previous);
            } else {
                MDC.remove(TraceId.MDC_KEY);
            }
        };
    }

    /** 解析 W3C traceparent（{@code 00-<traceId>-<spanId>-<flags>}）取 traceId；非法/缺失返回 null。 */
    private static String parseTraceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-", 4);
        if (parts.length < 2) {
            return null;
        }
        String traceId = parts[1];
        return (traceId == null || traceId.isBlank()) ? null : traceId;
    }

    private static String spanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
