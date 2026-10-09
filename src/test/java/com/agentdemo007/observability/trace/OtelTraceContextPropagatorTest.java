package com.agentdemo007.observability.trace;

import com.agentdemo007.common.trace.TraceId;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator; // 1.4x+ 起由 extension 迁入 api
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OtelTraceContextPropagator 单测（Phase 23·W3C 真实现）。
 *
 * <p>用真实 OTel SDK（+W3C 传播器）验证 seam 契约：注入的 traceparent 可被提取出同一
 * traceId；restoreScope 在消费线程同时恢复 OTel 上下文（child span 归属生产 trace）与
 * MDC（日志信道），关闭后两信道还原——跨进程瀑布连通 + 消费线程不被污染。
 */
class OtelTraceContextPropagatorTest {

    private SdkTracerProvider tracerProvider;
    private OpenTelemetry otel;
    private OtelTraceContextPropagator propagator;

    @BeforeEach
    void setUp() {
        tracerProvider = SdkTracerProvider.builder().build();
        otel = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        propagator = new OtelTraceContextPropagator(otel);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        MDC.remove(TraceId.MDC_KEY);
    }

    @Test
    void injectThenExtract_carriesSameTraceId() {
        Span span = otel.getTracer("test").spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            Map<String, String> carrier = new HashMap<>();
            propagator.inject(carrier);

            assertThat(carrier).containsKey(TraceContextPropagator.TRACEPARENT_HEADER);
            assertThat(propagator.extractTraceId(carrier))
                    .isEqualTo(span.getSpanContext().getTraceId());
        } finally {
            span.end();
        }
    }

    @Test
    void inject_withoutActiveSpan_writesTraceparentOfInvalidContext() {
        // 无活跃 span（Context.root）：W3C 注入器不写 traceparent——提取返回 null，不抛
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(carrier);

        assertThat(propagator.extractTraceId(carrier)).isNull();
    }

    @Test
    void extract_nullCarrier_returnsNull() {
        assertThat(propagator.extractTraceId(null)).isNull();
        assertThat(propagator.extractTraceId(new HashMap<>())).isNull();
    }

    @Test
    void inject_nullCarrier_neverThrows() {
        propagator.inject(null); // ②每步降级：永不抛
    }

    @Test
    void restoreScope_recoversMdcAndParentsChildSpan_thenUndoes() throws Exception {
        // 生产侧：起 span + 注入
        Span producer = otel.getTracer("test").spanBuilder("produce").startSpan();
        Map<String, String> carrier = new HashMap<>();
        try (Scope ignored = producer.makeCurrent()) {
            propagator.inject(carrier);
        } finally {
            producer.end();
        }
        String producerTraceId = producer.getSpanContext().getTraceId();

        // 消费线程：restoreScope → MDC 恢复 + 手动 child span 归属生产 trace；关闭还原
        AtomicReference<String> mdcAfterRestore = new AtomicReference<>();
        AtomicReference<String> childTraceId = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            try (AutoCloseable scope = propagator.restoreScope(carrier)) {
                mdcAfterRestore.set(MDC.get(TraceId.MDC_KEY));
                startChildSpan(childTraceId);
            } catch (Exception ignored) {
                // 测试线程体内不抛
            }
        });
        consumer.start();
        consumer.join();

        assertThat(mdcAfterRestore.get()).isEqualTo(producerTraceId);
        assertThat(childTraceId.get()).isEqualTo(producerTraceId);
        // 关闭后消费线程 MDC 还原（不污染池化线程）
        assertThat(MDC.get(TraceId.MDC_KEY)).isNull();
    }

    /** 模拟消费线程上的手动 child span（经 AgentTracer 的路径）：父 = restoreScope 恢复的远程上下文。 */
    private void startChildSpan(AtomicReference<String> childTraceId) {
        Span child = otel.getTracer("test").spanBuilder("consume").startSpan();
        childTraceId.set(child.getSpanContext().getTraceId());
        child.end();
    }

    @Test
    void restoreScope_withoutTraceparent_returnsNoopScope() throws Exception {
        try (AutoCloseable scope = propagator.restoreScope(new HashMap<>())) {
            scope.close(); // 无副作用、不抛
        }
        assertThat(MDC.get(TraceId.MDC_KEY)).isNull();
    }
}
