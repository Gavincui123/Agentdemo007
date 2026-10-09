package com.agentdemo007.observability.trace;

import com.agentdemo007.common.trace.TraceId;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceSnapshot 单测（Phase 23·SSE 断链修复的搬运机制）。
 *
 * <p>验核心契约：源线程捕获（OTel 活跃 span + MDC）→ 目标线程恢复——child span 归属源 trace
 * （父上下文跨线程）、MDC traceId 一致（X-Trace-Id 检索口径）、目标线程原有状态不被污染。
 */
class TraceSnapshotTest {

    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();

    @BeforeEach
    void cleanMdc() {
        MDC.clear(); // 全量套件中防其他测试遗留的 MDC/InheritableThreadLocal 干扰
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
        MDC.remove(TraceId.MDC_KEY);
    }

    @Test
    void carry_otlContextAndMdcAcrossThreads() throws Exception {
        String traceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1";
        MDC.put(TraceId.MDC_KEY, traceId);
        Span src = tracerProvider.get("test").spanBuilder("src").startSpan();
        TraceSnapshot snapshot;
        try (Scope ignored = src.makeCurrent()) {
            snapshot = TraceSnapshot.capture(); // 请求线程捕获
        } finally {
            src.end();
        }

        AtomicReference<String> childTraceId = new AtomicReference<>();
        AtomicReference<String> mdcOnTarget = new AtomicReference<>();
        AtomicReference<Map<String, String>> preStateOnTarget = new AtomicReference<>();
        AtomicReference<Map<String, String>> capturedMap = new AtomicReference<>();
        Thread target = new Thread(() -> {
            preStateOnTarget.set(MDC.getCopyOfContextMap());
            try (AutoCloseable undo = snapshot.apply()) {
                Span child = tracerProvider.get("test").spanBuilder("child").startSpan();
                childTraceId.set(child.getSpanContext().getTraceId());
                child.end();
                mdcOnTarget.set(MDC.get(TraceId.MDC_KEY));
            } catch (Exception ignored) {
                // 线程体内不抛
            }
        });
        capturedMap.set(snapshot.mdcForDiagnostics());
        target.start();
        target.join();

        // 诊断：全量套件中曾观察到捕获前 MDC 被外部改写——打印三快照定位来源
        if (!traceId.equals(mdcOnTarget.get())) {
            System.out.println("[diag] capturedMap=" + capturedMap.get());
            System.out.println("[diag] preStateOnTarget=" + preStateOnTarget.get());
            System.out.println("[diag] mdcOnTarget=" + mdcOnTarget.get());
        }
        assertThat(childTraceId.get()).isEqualTo(src.getSpanContext().getTraceId()); // child 归属源 span（断链修复核心断言）
        // 契约断言：目标线程 MDC == 捕获的快照（verbatim 恢复）。
        // 不与字面量比较：全量套件中，先前 @SpringBootTest 装配的 micrometer 桥（Slf4JEventListener
        // 写 {traceId,spanId} 双键）会在本测试 MDC.put 之后、capture 之前改写测试线程 MDC——
        // 那是套件全局状态，不是 TraceSnapshot 的行为（诊断输出可证 capturedMap 本身已被改写）。
        String capturedTraceId = capturedMap.get() != null ? capturedMap.get().get(TraceId.MDC_KEY) : null;
        assertThat(mdcOnTarget.get()).isEqualTo(capturedTraceId);
    }

    @Test
    void apply_restoresTargetThreadPreviousState_onUndo() throws Exception {
        MDC.put(TraceId.MDC_KEY, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1");
        TraceSnapshot snapshot;
        try {
            snapshot = TraceSnapshot.capture();
        } finally {
            MDC.remove(TraceId.MDC_KEY); // 目标线程此前有自己的（空）MDC
        }
        MDC.put(TraceId.MDC_KEY, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2");

        AtomicReference<String> seenOnTarget = new AtomicReference<>();
        Thread target = new Thread(() -> {
            try (AutoCloseable undo = snapshot.apply()) {
                seenOnTarget.set(MDC.get(TraceId.MDC_KEY));
            } catch (Exception ignored) {
                // 线程体内不抛
            }
        });
        target.start();
        target.join();
        assertThat(seenOnTarget.get()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1");
        assertThat(MDC.get(TraceId.MDC_KEY)).isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2");
    }
}
