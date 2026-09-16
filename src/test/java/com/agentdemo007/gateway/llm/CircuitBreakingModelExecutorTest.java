package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.StreamingReplyHandler;
import com.agentdemo007.gateway.exception.CircuitOpenException;
import com.agentdemo007.resilience.FatalException;
import com.agentdemo007.resilience.ModelCircuitBreaker;
import com.agentdemo007.resilience.ToolRecoverableException;
import com.agentdemo007.resilience.WindowedCircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 熔断装饰器单测：包 {@link RoutingModelExecutor}（或任意 delegate），OPEN→抛 {@link CircuitOpenException}，
 * 成功/失败按 {@link com.agentdemo007.resilience.ExceptionTriage} 记账（致命/工具异常不计入模型健康）。
 *
 * <p>关键语义：① OPEN 时直接抛、不调 delegate；② 普通失败计入熔断计数；③ 致命/工具异常<b>不计入</b>
 * （不是模型可用性问题，不应误熔断）；④ 冷却后半开探针成功恢复。
 */
class CircuitBreakingModelExecutorTest {

    private final long[] t = {0L};
    private final LongSupplier clock = () -> t[0];
    private final ModelCircuitBreaker breaker = new ModelCircuitBreaker(3, 100L, 50L, clock);
    private final com.agentdemo007.resilience.ExceptionTriage triage = new com.agentdemo007.resilience.ExceptionTriage();

    /** 可脚本化的委托执行器：可指定下一次 execute 抛什么、返回什么、被调几次。 */
    static class ScriptedDelegate implements ModelExecutor {
        volatile RuntimeException toThrow;
        volatile RuntimeException streamError; // 非 null → stream 调 onError（流式失败→熔断记账/恢复）
        int calls = 0;
        int streamCalls = 0;

        @Override
        public LlmResponse execute(LlmRequest request) {
            calls++;
            if (toThrow != null) {
                throw toThrow;
            }
            return new LlmResponse(request.modelId(), "ok", 1);
        }

        @Override
        public void stream(LlmRequest request, StreamingReplyHandler handler) {
            streamCalls++;
            if (streamError != null) {
                handler.onError(streamError);
                return;
            }
            handler.onPartialResponse("tok-A");
            handler.onCompleteResponse("tok-A tok-B", 3);
        }
    }

    @Test
    void openCircuitThrowsAndSkipsDelegate() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);
        // 预热熔断到 OPEN（3 次失败）
        breaker.recordFailure("m");
        breaker.recordFailure("m");
        breaker.recordFailure("m");
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> exec.execute(new LlmRequest("m", "hi", 1024)))
                .isInstanceOf(CircuitOpenException.class);
        assertThat(delegate.calls).isZero(); // 快速失败，未调 delegate
    }

    @Test
    void successDelegatesAndRecords() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);

        LlmResponse r = exec.execute(new LlmRequest("m", "hi", 1024));

        assertThat(r.content()).isEqualTo("ok");
        assertThat(delegate.calls).isEqualTo(1);
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
    }

    @Test
    void ordinaryFailureIsCountedAndRethrown() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.toThrow = new RuntimeException("500 Internal");
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);

        assertThatThrownBy(() -> exec.execute(new LlmRequest("m", "hi", 1024)))
                .isInstanceOf(RuntimeException.class);
        // 计入熔断：再 2 次（共 3）即 OPEN
        breaker.recordFailure("m");
        breaker.recordFailure("m");
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.OPEN);
    }

    @Test
    void fatalExceptionNotCountedAsModelHealth() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.toThrow = new FatalException("检测到注入");
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);

        assertThatThrownBy(() -> exec.execute(new LlmRequest("m", "hi", 1024)))
                .isInstanceOf(FatalException.class);
        // 致命异常不计入熔断——breaker 仍 CLOSED，未误熔断
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
    }

    @Test
    void toolRecoverableNotCountedAsModelHealth() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.toThrow = new ToolRecoverableException("参数类型不匹配");
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);

        assertThatThrownBy(() -> exec.execute(new LlmRequest("m", "hi", 1024)))
                .isInstanceOf(ToolRecoverableException.class);
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenProbeSuccessRecovers() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);
        breaker.recordFailure("m");
        breaker.recordFailure("m");
        breaker.recordFailure("m"); // OPEN
        t[0] = 50L; // 冷却到期 → HALF_OPEN
        LlmResponse r = exec.execute(new LlmRequest("m", "hi", 1024)); // 探针
        assertThat(r.content()).isEqualTo("ok");
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.CLOSED); // 探针成功恢复
    }

    // ---- [[q2-token-streaming]] 流式：stream 须转发到 delegate.stream（修生产链在熔断装饰层断裂） ----
    // 生产 @Bean ModelExecutor = CircuitBreakingModelExecutor(RoutingModelExecutor)，缺 stream 覆写→
    // 命中 ModelExecutor.stream 默认 throw UOE「此 ModelExecutor 不支持流式」→ OutputStep 全量回退阻塞。

    @Test
    void stream_delegatesTokensThrough() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);
        List<String> tokens = new ArrayList<>();
        AtomicReference<String> full = new AtomicReference<>();
        StreamingReplyHandler handler = new StreamingReplyHandler() {
            @Override public void onPartialResponse(String token) { tokens.add(token); }
            @Override public void onCompleteResponse(String fullReply, int tokens) { full.set(fullReply); }
            @Override public void onError(Throwable error) { }
        };

        exec.stream(new LlmRequest("m", "hi", 1024), handler);

        assertThat(delegate.streamCalls).isEqualTo(1); // 转发到 delegate.stream（非默认 UOE）
        assertThat(tokens).containsExactly("tok-A"); // 逐 token 透传
        assertThat(full.get()).isEqualTo("tok-A tok-B"); // 终态全文透传
    }

    @Test
    void stream_openCircuitThrowsAndSkipsDelegate() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);
        breaker.recordFailure("m");
        breaker.recordFailure("m");
        breaker.recordFailure("m"); // OPEN
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> exec.stream(new LlmRequest("m", "hi", 1024), noopHandler()))
                .isInstanceOf(CircuitOpenException.class);
        assertThat(delegate.streamCalls).isZero(); // 快速失败，未调 delegate
    }

    @Test
    void stream_onCompleteRecordsSuccessAndRecoversHalfOpen() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);
        breaker.recordFailure("m");
        breaker.recordFailure("m");
        breaker.recordFailure("m"); // OPEN
        t[0] = 50L; // HALF_OPEN（冷却到期）

        exec.stream(new LlmRequest("m", "hi", 1024), noopHandler()); // 探针流式成功

        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.CLOSED); // onComplete 记成功→恢复
    }

    @Test
    void stream_modelAvailabilityErrorRecordsFailure() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.streamError = new RuntimeException("500 Internal");
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);
        breaker.recordFailure("m");
        breaker.recordFailure("m"); // 2 次失败
        AtomicReference<Throwable> err = new AtomicReference<>();

        exec.stream(new LlmRequest("m", "hi", 1024), handlerOnError(err));

        assertThat(err.get()).isInstanceOf(RuntimeException.class); // 错误透传给调用方
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.OPEN); // 第 3 次失败→OPEN
    }

    @Test
    void stream_fatalErrorNotCountedAsModelHealth() {
        ScriptedDelegate delegate = new ScriptedDelegate();
        delegate.streamError = new FatalException("检测到注入");
        CircuitBreakingModelExecutor exec = new CircuitBreakingModelExecutor(delegate, breaker, triage);
        AtomicReference<Throwable> err = new AtomicReference<>();

        exec.stream(new LlmRequest("m", "hi", 1024), handlerOnError(err));

        assertThat(err.get()).isInstanceOf(FatalException.class);
        assertThat(breaker.state("m")).isEqualTo(WindowedCircuitBreaker.State.CLOSED); // 致命不计入熔断
    }

    /** 空 handler（流式成功路径只验转发/记账，不消费 token）。 */
    private static StreamingReplyHandler noopHandler() {
        return new StreamingReplyHandler() {
            @Override public void onPartialResponse(String token) { }
            @Override public void onCompleteResponse(String fullReply, int tokens) { }
            @Override public void onError(Throwable error) { }
        };
    }

    /** 捕获 onError 的 handler（流式失败路径验错误透传）。 */
    private static StreamingReplyHandler handlerOnError(AtomicReference<Throwable> err) {
        return new StreamingReplyHandler() {
            @Override public void onPartialResponse(String token) { }
            @Override public void onCompleteResponse(String fullReply, int tokens) { }
            @Override public void onError(Throwable error) { err.set(error); }
        };
    }
}
