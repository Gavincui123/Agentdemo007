package com.agentdemo007.observability.trace;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;

import java.util.Map;

/**
 * 观测上下文跨线程快照（Phase 23·SSE 断链修复）。
 *
 * <p>动机：{@code /chat/stream} 把流水线扔到 sse- 线程池执行，此前两处断链——
 * ①OTel context（HTTP server span）不随线程池传播，sse 线程上开出的 span 变成
 * 无父孤立 trace，瀑布图断根；②MDC 不随线程池传播，{@code PipelineContext} 在 sse 线程
 * 新建时 {@code TraceId.current()} 兜底生成新 UUID，与响应头 {@code X-Trace-Id} 不一致
 * （前端展示的 traceId 在 Jaeger 检索不到该轮）。本类在请求线程捕获、目标线程恢复，
 * 双信道一次修平。
 *
 * <p>口径与 {@code ChatSubjectHolder} 的 set/snapshot/restore 同哲学：ThreadLocal 不随
 * 线程池传播，显式搬运是唯一正解（不依赖 TaskDecorator——{@code SimpleAsyncTaskExecutor}
 * 每流一线程，装饰器方案对既有装配侵入更大）。
 *
 * <p>用法：请求线程 {@code TraceSnapshot snapshot = TraceSnapshot.capture();} →
 * 目标线程 {@code try (AutoCloseable undo = snapshot.apply()) { ... } finally 还原}。
 * OTel API（Context/Scope）在 no-OTel 装配下同样可用（root context + no-op scope），
 * 本类无条件可用、不挂开关。
 */
public final class TraceSnapshot {

    private final Context otelContext;
    private final Map<String, String> mdc;

    private TraceSnapshot(Context otelContext, Map<String, String> mdc) {
        this.otelContext = otelContext;
        this.mdc = mdc;
    }

    /** 源线程调用：捕获当前 OTel 上下文（含活跃 span）与 MDC 快照。 */
    public static TraceSnapshot capture() {
        return new TraceSnapshot(Context.current(), MDC.getCopyOfContextMap());
    }

    /** 诊断用（包级）：捕获到的 MDC 快照内容，勿用于业务。 */
    Map<String, String> mdcForDiagnostics() {
        return mdc;
    }

    /**
     * 目标线程调用：恢复快照为 current，返回撤销句柄——finally 关闭后还原目标线程
     * 调用前的 OTel context 与 MDC 原状（不污染池化线程）。
     */
    public AutoCloseable apply() {
        Scope otelScope = otelContext.makeCurrent();
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (mdc != null) {
            MDC.setContextMap(mdc);
        } else {
            MDC.clear();
        }
        return () -> {
            otelScope.close();
            if (previous != null) {
                MDC.setContextMap(previous);
            } else {
                MDC.clear();
            }
        };
    }
}
