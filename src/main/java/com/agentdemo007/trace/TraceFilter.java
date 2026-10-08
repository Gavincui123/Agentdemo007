package com.agentdemo007.trace;

import com.agentdemo007.common.trace.TraceId;
import com.agentdemo007.observability.trace.TraceContextPropagator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器。
 *
 * <p>生成或透传入站 {@code X-Trace-Id}，写入 MDC（键 {@code traceId}）供
 * {@link com.agentdemo007.common.response.UnifiedResponse} 取用，并在响应头回写。
 * traceId 贯穿 HTTP → 异常处理器 → 日志（logback {@code %X{traceId}}）全链路。
 *
 * <p>Phase 23 OTel 对齐：入站解析优先级 X-Trace-Id（既有契约，向后兼容）→
 * {@code traceparent}（W3C，外部网关/客户端标准传播头，取其 traceId 段）→ 生成；
 * 非 32-hex 一律视为非法重新生成（{@link TraceId#normalize}）——OTel 的
 * {@code MdcBackedIdGenerator} 会以 MDC 值充当 OTel traceId，非法值混入会破坏 W3C
 * 格式、Jaeger 检索不到。入站带 traceparent 时，Boot 自动装配的 server span 经 W3C
 * 提取继承同一 traceId，与本过滤器写入 MDC 的值天然一致，无需额外接线。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceId.normalize(request.getHeader(TRACE_ID_HEADER));
        if (traceId == null) {
            traceId = traceparentTraceId(request.getHeader(TraceContextPropagator.TRACEPARENT_HEADER));
        }
        if (traceId == null) {
            traceId = generateTraceId();
        }
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /** 解析 W3C traceparent（{@code <version>-<traceId 32hex>-<spanId>-<flags>}）取 traceId 段；非法/缺失返回 null。 */
    private String traceparentTraceId(String traceparent) {
        if (!StringUtils.hasText(traceparent)) {
            return null;
        }
        String[] parts = traceparent.trim().split("-", 4);
        return (parts.length >= 2) ? TraceId.normalize(parts[1]) : null;
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
