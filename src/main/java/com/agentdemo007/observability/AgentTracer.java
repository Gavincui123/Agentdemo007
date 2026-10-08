package com.agentdemo007.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 链路埋点收口门面（Phase 23·OpenTelemetry 集成）。
 *
 * <p>④统一收口：所有手动 span 只经此门面创建——同 {@link AgentMetrics} 之于指标、
 * {@link com.agentdemo007.observability.trace.TraceContextPropagator} 之于 trace 传播，
 * 禁止业务类散落 {@code io.opentelemetry.*} 直接操作（OTel API 的使用仅限 observability
 * 包内的门面/传播/配置类），业务代码只持有本门面与 {@link SpanHandle}，换 tracer 实现不换业务代码。
 *
 * <p>分工：基础设施 span（HTTP server/client、JDBC）由 Boot starter 自动装配产出；
 * 本门面管高价值业务 span——15 步流水线（root + per-step）、LLM 网关（gen_ai 语义约定）、
 * RAG 漏斗、MQ 消费。span 命名与属性约定集中于此处注释与各调用点（与「LLM出站」日志行同点位同口径）。
 *
 * <p>②每步降级：Tracer 缺失（非 Spring 单测 / OTel 未装配）时 {@link #NO_OP} 空实现，
 * span 句柄永不外泄、永不为 null，所有方法零副作用；span 创建/结束失败不影响主链路
 * （OTel API 内部对 no-op/未采样 span 自身已零开销，门面不做二次 try-catch 包夹）。
 */
@Component
public class AgentTracer {

    /** 空实现：业务代码在非 Spring 单测 / OTel 未装配场景持有，零副作用。 */
    public static final AgentTracer NO_OP = new AgentTracer((Tracer) null);

    /** instrumentation 名（Jaeger 内归属 span 来源，与 spring.application.name 同口径）。 */
    static final String INSTRUMENTATION_NAME = "Agentdemo007";

    private final Tracer tracer;

    /** Spring 装配：OTel starter 在 classpath 即有 Tracer bean（导出与否由 OTLP_ENABLED 另行控制）。 */
    @Autowired
    public AgentTracer(ObjectProvider<Tracer> tracerProvider) {
        this(tracerProvider.getIfAvailable());
    }

    /** 显式构造（测试便利）；tracer 为 null 即空实现。 */
    public AgentTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    /** OTel 链路是否生效（真 tracer 且采样开启）。 */
    public boolean enabled() {
        return tracer != null && tracer.isEnabled();
    }

    /**
     * 开一个业务 span（默认父 = 当前上下文，跟随 HTTP server span / MQ 传播恢复的父）。
     *
     * @param name    span 名（约定：{@code agent.pipeline} / {@code agent.step} / {@code gen_ai.chat} /
     *                {@code agent.rag.<阶段>} / {@code agent.mq.<queue>}.process）
     * @param kind    span 类别（业务段 INTERNAL；LLM 出站 CLIENT；MQ 消费 CONSUMER）
     * @param attrKv  属性键值对（成对出现，如 {@code "agent.scene", "query_rewrite"}）
     * @return span 句柄（用毕必须 {@link SpanHandle#end()}；推荐 try-finally 包夹）
     */
    public SpanHandle startSpan(String name, SpanKind kind, String... attrKv) {
        if (tracer == null) {
            return SpanHandle.NO_OP_HANDLE;
        }
        try {
            SpanBuilder builder = tracer.spanBuilder(name).setSpanKind(kind);
            for (int i = 0; i + 1 < attrKv.length; i += 2) {
                if (attrKv[i + 1] != null) {
                    builder.setAttribute(attrKv[i], attrKv[i + 1]);
                }
            }
            return new SpanHandle(builder.startSpan());
        } catch (Exception e) {
            // ② best-effort：埋点故障不影响业务（理论不可达，OTel API 自身不抛；防御性收口）
            return SpanHandle.NO_OP_HANDLE;
        }
    }

    /**
     * span 句柄：包装 OTel {@link Span}，业务代码不感知 OTel 类型。
     * 所有方法对 NO_OP 句柄零副作用。
     */
    public static final class SpanHandle implements AutoCloseable {

        static final SpanHandle NO_OP_HANDLE = new SpanHandle(null);

        private final Span span;

        private SpanHandle(Span span) {
            this.span = span;
        }

        /** 字符串属性（value 为 null 时忽略——保持 span 干净，不落 "null" 字面量）。 */
        public void attr(String key, String value) {
            if (span != null && value != null) {
                span.setAttribute(key, value);
            }
        }

        /** 数值属性（耗时/尝试次数/token 数等）。 */
        public void attr(String key, long value) {
            if (span != null) {
                span.setAttribute(key, value);
            }
        }

        /** 异常收口：记录异常 + 置 ERROR 状态（与「LLM出站 ok=false」日志行同口径）。 */
        public void error(Throwable t) {
            if (span != null && t != null) {
                span.recordException(t);
                span.setStatus(StatusCode.ERROR, t.getMessage());
            }
        }

        /** 结束 span（耗时由此刻与起点之差自动记录，无需手工传值）。 */
        public void end() {
            if (span != null) {
                span.end();
            }
        }

        /**
         * 把本 span 置为当前上下文（root span 用：后续 startSpan 的默认父 = 本 span）。
         * 调用方负责 close 返回的 Scope（顺序：先 end 子 span，再关 scope，最后 end 本 span）。
         */
        public io.opentelemetry.context.Scope makeCurrent() {
            return (span != null) ? span.makeCurrent() : io.opentelemetry.context.Scope.noop();
        }

        /** try-with-resources 口径，同 {@link #end()}。 */
        @Override
        public void close() {
            end();
        }

        /** 当前 span 所属 traceId（no-op / 非 recording 返回 null——调用方勿依赖）。 */
        public String traceId() {
            return (span != null && span.isRecording()) ? span.getSpanContext().getTraceId() : null;
        }
    }
}
