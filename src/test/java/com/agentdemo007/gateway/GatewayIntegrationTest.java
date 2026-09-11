package com.agentdemo007.gateway;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import com.agentdemo007.gateway.core.GatewayRequest;
import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.TokenBudgetChecker;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import com.agentdemo007.gateway.core.FailoverExecutor;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import com.agentdemo007.gateway.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一模型网关集成测试（Phase 4·第六层内核：预算关卡 + 故障转移 + 执行收口）。
 *
 * <p>以可编程 {@link StubExecutor} 替代真实 LLM，断言四条主干行为：
 * 正常透传、主模型故障→备选转移成功、全备选耗尽→{@link LlmUnavailableException}、
 * 速率超限→{@link RateLimitExceededException}（由上层收口为话术，不调用模型）。
 */
class GatewayIntegrationTest {

    @Test
    void invoke_primaryReturns_responsePropagatedAndTokensRecorded() {
        StubExecutor exec = new StubExecutor();
        exec.stub("primary", new LlmResponse("primary", "answer", 50));
        UnifiedModelGateway gw = gateway(exec, noLimitPolicy());

        LlmResponse resp = gw.invoke(request("primary", "你好"));

        assertThat(resp.content()).isEqualTo("answer");
        assertThat(resp.modelId()).isEqualTo("primary");
        assertThat(gw.budget().tokensToday()).isEqualTo(50);
    }

    @Test
    void invoke_primaryFails_failoverToFallback_succeeds() {
        StubExecutor exec = new StubExecutor();
        exec.stub("primary", new RuntimeException("timeout"));
        exec.stub("fb1", new LlmResponse("fb1", "ok", 10));
        UnifiedModelGateway gw = gateway(exec, noLimitPolicy());

        LlmResponse resp = gw.invoke(requestWithFailover("primary", "fb1"));

        assertThat(resp.modelId()).isEqualTo("fb1");
        assertThat(resp.content()).isEqualTo("ok");
    }

    @Test
    void invoke_allFailoversExhausted_throwsUnavailable() {
        StubExecutor exec = new StubExecutor();
        exec.stub("primary", new RuntimeException("e1"));
        exec.stub("fb1", new RuntimeException("e2"));
        UnifiedModelGateway gw = gateway(exec, noLimitPolicy());

        assertThatThrownBy(() -> gw.invoke(requestWithFailover("primary", "fb1")))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void invoke_rateExceeded_throwsRateLimitedWithoutCallingModel() {
        StubExecutor exec = new StubExecutor();
        exec.stub("primary", new LlmResponse("primary", "r", 1));
        FlowControlPolicy rateOne = new FlowControlPolicy("fc", 1, 100000, Duration.ofSeconds(60));
        UnifiedModelGateway gw = new UnifiedModelGateway(exec, new TokenBudgetChecker(),
                new FailoverExecutor());

        gw.invoke(new GatewayRequest("primary", "a", 10,
                new FailoverPolicy.Builder("fo").build(), rateOne)); // 第 1 次：占用名额

        assertThatThrownBy(() -> gw.invoke(new GatewayRequest("primary", "b", 10,
                new FailoverPolicy.Builder("fo").build(), rateOne))) // 第 2 次：超速率
                .isInstanceOf(RateLimitExceededException.class);
        assertThat(exec.callCount).isEqualTo(1); // 超限时未调用模型
    }

    // ---- helpers ----

    private static UnifiedModelGateway gateway(StubExecutor exec, FlowControlPolicy fc) {
        return new UnifiedModelGateway(exec, new TokenBudgetChecker(), new FailoverExecutor());
    }

    private static FlowControlPolicy noLimitPolicy() {
        return new FlowControlPolicy("nolimit", Integer.MAX_VALUE, Integer.MAX_VALUE,
                Duration.ofSeconds(60));
    }

    private static GatewayRequest request(String modelId, String prompt) {
        return new GatewayRequest(modelId, prompt, 100,
                new FailoverPolicy.Builder(modelId).build(), noLimitPolicy());
    }

    private static GatewayRequest requestWithFailover(String primary, String fallback) {
        return new GatewayRequest(primary, "x", 100,
                new FailoverPolicy.Builder("fo").maxRetries(3).fallbackModelIds(List.of(fallback)).build(),
                noLimitPolicy());
    }

    static class StubExecutor implements ModelExecutor {
        final Map<String, Object> stubs = new HashMap<>();
        int callCount = 0;

        void stub(String modelId, Object result) {
            stubs.put(modelId, result);
        }

        @Override
        public LlmResponse execute(LlmRequest request) {
            callCount++;
            Object result = stubs.get(request.modelId());
            if (result instanceof LlmResponse r) return r;
            if (result instanceof RuntimeException e) throw e;
            if (result == null) throw new RuntimeException("无桩：" + request.modelId());
            throw new RuntimeException("非法桩类型");
        }
    }
}
