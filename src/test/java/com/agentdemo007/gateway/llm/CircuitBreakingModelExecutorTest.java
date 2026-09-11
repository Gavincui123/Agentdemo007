package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.exception.CircuitOpenException;
import com.agentdemo007.resilience.FatalException;
import com.agentdemo007.resilience.ModelCircuitBreaker;
import com.agentdemo007.resilience.ToolRecoverableException;
import com.agentdemo007.resilience.WindowedCircuitBreaker;
import org.junit.jupiter.api.Test;

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
        int calls = 0;

        @Override
        public LlmResponse execute(LlmRequest request) {
            calls++;
            if (toThrow != null) {
                throw toThrow;
            }
            return new LlmResponse(request.modelId(), "ok", 1);
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
}
