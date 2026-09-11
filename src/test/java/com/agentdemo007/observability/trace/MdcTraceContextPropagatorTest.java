package com.agentdemo007.observability.trace;

import com.agentdemo007.common.trace.TraceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link MdcTraceContextPropagator} 测评（Phase 15·跨进程 traceId 不断，④统一收口 + §5.14 引擎无关）。
 *
 * <p>dev/no-OTel 实现：以 {@link TraceId#current()}（MDC）合成 W3C {@code traceparent}，
 * 消费侧 {@link #restoreScope} 回写 MDC 使消费线程的 {@code TraceId.current()} 与生产侧一致。
 * 全程 best-effort（②每步降级）：null carrier / 缺失 traceparent / 非法格式均不抛、不影响投递/消费。
 *
 * <p>prod 实现待加 OTel 依赖后委托 {@code W3CTraceContextPropagator}——本 seam 形状与之对齐（carrier=Map），
 * 换实现不换收口（§5.14）。
 */
class MdcTraceContextPropagatorTest {

    private static final String TRACE_ID = "0af7651916cd43dd831469c8b1c56dc0"; // 32 hex（W3C traceId）
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-e9f5a2b1074d4e1f-01"; // 00-<traceId>-<span>-<flags>

    private final TraceContextPropagator propagator = new MdcTraceContextPropagator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void inject_writesTraceparentCarryingCurrentTraceId() {
        MDC.put(TraceId.MDC_KEY, TRACE_ID);
        Map<String, String> carrier = new HashMap<>();

        propagator.inject(carrier);

        String tp = carrier.get(TraceContextPropagator.TRACEPARENT_HEADER);
        assertThat(tp).isNotNull();
        assertThat(tp).startsWith("00-" + TRACE_ID + "-"); // 形如 00-<traceId>-<span>-<flags>
        assertThat(propagator.extractTraceId(carrier)).isEqualTo(TRACE_ID); // round-trip
    }

    @Test
    void extractTraceId_parsesW3cTraceparent_returnsNullWhenAbsent() {
        assertThat(propagator.extractTraceId(carrierOf(TRACEPARENT))).isEqualTo(TRACE_ID);
        assertThat(propagator.extractTraceId(new HashMap<>())).isNull(); // 缺失
        assertThat(propagator.extractTraceId(carrierOf("malformed"))).isNull(); // 非法格式
        assertThat(propagator.extractTraceId(null)).isNull(); // null carrier
    }

    @Test
    void restoreScope_makesCurrentTraceIdMatchDuringScope_andRestoresAfter() {
        // 初始 MDC 无 traceId
        assertThat(MDC.get(TraceId.MDC_KEY)).isNull();

        try (AutoCloseable scope = propagator.restoreScope(carrierOf(TRACEPARENT))) {
            // 消费线程恢复为 producer 的 traceId——跨进程 traceId 不断
            assertThat(TraceId.current()).isEqualTo(TRACE_ID);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        // scope 关闭后还原：MDC 回到调用前状态（null），不被污染
        assertThat(MDC.get(TraceId.MDC_KEY)).isNull();
    }

    @Test
    void restoreScope_noTraceparent_isNoOpAndDoesNotTouchMdc() {
        assertThat(MDC.get(TraceId.MDC_KEY)).isNull();

        try (AutoCloseable scope = propagator.restoreScope(new HashMap<>())) {
            // 无 traceparent → 不恢复，TraceId.current() 兜底随机生成（非 TRACE_ID）
            assertThat(TraceId.current()).isNotEqualTo(TRACE_ID);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThat(MDC.get(TraceId.MDC_KEY)).isNull(); // 仍无副作用
    }

    @Test
    void inject_bestEffort_neverThrowsOnNullCarrier() {
        assertThatCode(() -> propagator.inject(null)).doesNotThrowAnyException();
    }

    @Test
    void noOpPropagator_isSafeSink_neverThrows() {
        TraceContextPropagator noop = TraceContextPropagator.NO_OP;
        assertThatCode(() -> noop.inject(null)).doesNotThrowAnyException();
        assertThat(noop.extractTraceId(carrierOf(TRACEPARENT))).isNull();
        assertThatCode(() -> {
            try (AutoCloseable scope = noop.restoreScope(carrierOf(TRACEPARENT))) {
                // scope 关闭不抛
            }
        }).doesNotThrowAnyException();
    }

    private static Map<String, String> carrierOf(String traceparent) {
        Map<String, String> carrier = new HashMap<>();
        if (traceparent != null) {
            carrier.put(TraceContextPropagator.TRACEPARENT_HEADER, traceparent);
        }
        return carrier;
    }
}
