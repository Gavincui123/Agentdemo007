package com.agentdemo007.gateway.core;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.resilience.BackoffStrategy;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.FatalException;
import com.agentdemo007.resilience.NonRetryableException;
import com.agentdemo007.resilience.ResilientExecutor;
import com.agentdemo007.resilience.RetryPolicy;
import com.agentdemo007.resilience.Sleeper;
import com.agentdemo007.resilience.ToolRecoverableException;
import com.agentdemo007.resilience.TransientException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 故障转移执行器测试（Phase 5·韧性层：重试=同模型退避，耗尽=切备选，故障转移=换模型）。
 *
 * <p>验证经 {@link ResilientExecutor} 包裹后的故障转移语义：
 * <ul>
 *   <li>瞬态异常在<b>同一模型</b>退避重试，成功则不故障转移；</li>
 *   <li>同模型重试耗尽 → 切下一个备选模型；</li>
 *   <li>不可重试客户端错（403）→ 不重试同模型，但仍切备选（不同凭证/端点）；</li>
 *   <li>全部候选耗尽 → {@link LlmUnavailableException}（末次 cause）；</li>
 *   <li>{@link FatalException} / {@link ToolRecoverableException} → 立即传播，不故障转移
 *       （致命由上层审计拦截；工具错由 ToolErrorFeedback 处理）。</li>
 * </ul>
 *
 * <p>注入 {@link ScriptedExecutor}（按序返回响应/抛异常 + 记录每次 modelId）、
 * {@link CapturingSleeper}（不真实睡眠）、{@link FixedRandom}（确定性抖动）。
 */
class FailoverExecutorTest {

    private final ExceptionTriage triage = new ExceptionTriage();
    private final BackoffStrategy backoff = new BackoffStrategy();
    private final FailoverExecutor executor = new FailoverExecutor(
            new ResilientExecutor(triage, backoff, new CapturingSleeper(), new FixedRandom(0.0)),
            RetryPolicy.defaults(), triage);

    @Test
    void primarySucceeds_noFailover() {
        ScriptedExecutor exec = new ScriptedExecutor(new LlmResponse("primary", "ok", 1));
        LlmResponse resp = executor.execute(requestWithFailover("primary", "fb1"), exec);
        assertThat(resp.content()).isEqualTo("ok");
        assertThat(exec.calledModelIds).containsExactly("primary");
    }

    @Test
    void transientRetriedOnSameModel_thenSucceeds_noFailover() {
        ScriptedExecutor exec = new ScriptedExecutor(
                new TransientException("429"), new TransientException("429"),
                new LlmResponse("primary", "ok", 1));
        LlmResponse resp = executor.execute(requestWithFailover("primary", "fb1"), exec);
        assertThat(resp.content()).isEqualTo("ok");
        assertThat(exec.calledModelIds).containsExactly("primary", "primary", "primary"); // 不切备选
    }

    @Test
    void transientExhausted_failoverToNextCandidate() {
        ScriptedExecutor exec = new ScriptedExecutor(
                new TransientException("429"), new TransientException("429"), new TransientException("429"),
                new LlmResponse("fb1", "ok", 1));
        LlmResponse resp = executor.execute(requestWithFailover("primary", "fb1"), exec);
        assertThat(resp.modelId()).isEqualTo("fb1");
        assertThat(exec.calledModelIds).containsExactly("primary", "primary", "primary", "fb1");
    }

    @Test
    void nonRetryableClient_failoverImmediately() {
        ScriptedExecutor exec = new ScriptedExecutor(
                new NonRetryableException("403"),
                new LlmResponse("fb1", "ok", 1));
        LlmResponse resp = executor.execute(requestWithFailover("primary", "fb1"), exec);
        assertThat(resp.modelId()).isEqualTo("fb1");
        assertThat(exec.calledModelIds).containsExactly("primary", "fb1"); // 403 不重试同模型，直接切备选
    }

    @Test
    void allCandidatesExhausted_throwsLlmUnavailable() {
        ScriptedExecutor exec = new ScriptedExecutor(
                new TransientException("429"), new TransientException("429"), new TransientException("429"),
                new TransientException("429"), new TransientException("429"), new TransientException("429"));
        assertThatThrownBy(() -> executor.execute(requestWithFailover("primary", "fb1"), exec))
                .isInstanceOf(LlmUnavailableException.class);
        assertThat(exec.calledModelIds).containsExactly(
                "primary", "primary", "primary", "fb1", "fb1", "fb1");
    }

    @Test
    void fatalException_propagates_noFailover() {
        ScriptedExecutor exec = new ScriptedExecutor(new FatalException("injection"));
        assertThatThrownBy(() -> executor.execute(requestWithFailover("primary", "fb1"), exec))
                .isInstanceOf(FatalException.class);
        assertThat(exec.calledModelIds).containsExactly("primary"); // 不切备选
    }

    @Test
    void toolRecoverable_propagates_noFailover() {
        ScriptedExecutor exec = new ScriptedExecutor(new ToolRecoverableException("bad args"));
        assertThatThrownBy(() -> executor.execute(requestWithFailover("primary", "fb1"), exec))
                .isInstanceOf(ToolRecoverableException.class);
        assertThat(exec.calledModelIds).containsExactly("primary");
    }

    @Test
    void execute_recordsModelCallAndFailoverCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        FailoverExecutor fe = new FailoverExecutor(
                new ResilientExecutor(triage, backoff, new CapturingSleeper(), new FixedRandom(0.0)),
                RetryPolicy.defaults(), triage, metrics);

        // 1. 主模型成功
        fe.execute(requestWithFailover("primary", "fb1"),
                new ScriptedExecutor(new LlmResponse("primary", "ok", 1)));
        // 2. 故障转移成功（主瞬态耗尽→fb1 成功）
        fe.execute(requestWithFailover("primary", "fb1"),
                new ScriptedExecutor(new TransientException("429"), new TransientException("429"),
                        new TransientException("429"), new LlmResponse("fb1", "ok", 1)));
        // 3. 候选耗尽
        assertThatThrownBy(() -> fe.execute(requestWithFailover("primary", "fb1"),
                new ScriptedExecutor(new TransientException("429"), new TransientException("429"),
                        new TransientException("429"), new TransientException("429"),
                        new TransientException("429"), new TransientException("429"))))
                .isInstanceOf(LlmUnavailableException.class);

        // 场景1+2 各一次成功调用；场景2 主 + 场景3 两候选 三次失败调用
        assertThat(registry.counter("agent.gateway.calls", "success", "true").count()).isEqualTo(2.0);
        assertThat(registry.counter("agent.gateway.calls", "success", "false").count()).isEqualTo(3.0);
        assertThat(registry.timer("agent.gateway.call.duration").count()).isEqualTo(5L); // 2 成功 + 3 失败
        assertThat(registry.counter("agent.failover", "outcome", "success").count()).isEqualTo(1.0); // 场景2
        assertThat(registry.counter("agent.failover", "outcome", "exhausted").count()).isEqualTo(1.0); // 场景3
    }

    // ---- helpers ----

    private static GatewayRequest requestWithFailover(String primary, String fallback) {
        return new GatewayRequest(primary, "x", 100,
                new FailoverPolicy.Builder("fo").maxRetries(3).fallbackModelIds(List.of(fallback)).build(),
                noLimit());
    }

    private static FlowControlPolicy noLimit() {
        return new FlowControlPolicy("nolimit", Integer.MAX_VALUE, Integer.MAX_VALUE,
                Duration.ofSeconds(60));
    }

    static class ScriptedExecutor implements ModelExecutor {
        final List<String> calledModelIds = new ArrayList<>();
        private final Queue<Object> outcomes;

        ScriptedExecutor(Object... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public LlmResponse execute(LlmRequest request) {
            calledModelIds.add(request.modelId());
            Object o = outcomes.poll();
            if (o instanceof RuntimeException e) throw e;
            if (o instanceof LlmResponse r) return r;
            throw new IllegalStateException("脚本耗尽或非法桩类型");
        }
    }

    static class CapturingSleeper implements Sleeper {
        final List<Long> delays = new ArrayList<>();

        @Override
        public void sleepMs(long millis) {
            delays.add(millis);
        }
    }

    static class FixedRandom extends Random {
        private final double value;

        FixedRandom(double value) {
            super();
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
