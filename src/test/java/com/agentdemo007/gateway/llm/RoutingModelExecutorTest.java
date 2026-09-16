package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.StreamingReplyHandler;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由执行器单测：合成 modelId→(per-provider 执行器, raw 模型串) 翻译委托。
 *
 * <p>验证：① 合成 id 翻译成 provider 的 raw 模型串并委托对应执行器；② 未注册 id→ModelSelectionException。
 */
class RoutingModelExecutorTest {

    /** 捕获委托到的执行器收到的 LlmRequest（验证 raw 模型串被正确翻译）。 */
    static class CapturingExecutor implements ModelExecutor {
        final String name;
        List<LlmRequest> received = new ArrayList<>();

        CapturingExecutor(String name) { this.name = name; }

        @Override
        public LlmResponse execute(LlmRequest request) {
            received.add(request);
            return new LlmResponse(request.modelId(), "ok-" + name, 1);
        }

        @Override
        public void stream(LlmRequest request, StreamingReplyHandler handler) {
            received.add(request);
            handler.onPartialResponse("tok-1-" + name);
            handler.onPartialResponse("tok-2-" + name);
            handler.onCompleteResponse("tok-1-" + name + "tok-2-" + name, 2);
        }
    }

    @Test
    void routesSyntheticIdToProviderRawModel() {
        CapturingExecutor siliconflow = new CapturingExecutor("siliconflow");
        CapturingExecutor sensenova = new CapturingExecutor("sensenova");
        RoutingModelExecutor router = new RoutingModelExecutor(Map.of(
                "siliconflow-large", new RoutingModelExecutor.Route(siliconflow, "Qwen/Qwen3.5-35B-A3B"),
                "sensenova-large", new RoutingModelExecutor.Route(sensenova, "deepseek-v4-flash")));

        LlmResponse resp = router.execute(new LlmRequest("siliconflow-large", "hi", 1024));

        assertThat(resp.content()).isEqualTo("ok-siliconflow");
        assertThat(siliconflow.received).hasSize(1);
        // 关键：发给 provider 的 raw 模型串，非合成 id
        assertThat(siliconflow.received.get(0).modelId()).isEqualTo("Qwen/Qwen3.5-35B-A3B");
        assertThat(sensenova.received).isEmpty();

        // 切备
        router.execute(new LlmRequest("sensenova-large", "hi", 512));
        assertThat(sensenova.received.get(0).modelId()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void unregisteredModelIdThrowsSelectionException() {
        RoutingModelExecutor router = new RoutingModelExecutor(Map.of());
        assertThatThrownBy(() -> router.execute(new LlmRequest("nobody", "hi", 1024)))
                .isInstanceOf(ModelSelectionException.class);
    }

    // ---- [[q2-token-streaming]] 流式：合成 id 翻 raw 串 + 委托 route.executor().stream ----

    @Test
    void stream_translatesSyntheticIdAndDelegatesToRouteStream() {
        CapturingExecutor siliconflow = new CapturingExecutor("siliconflow");
        RoutingModelExecutor router = new RoutingModelExecutor(Map.of(
                "siliconflow-large", new RoutingModelExecutor.Route(siliconflow, "Qwen/Qwen3.5-35B-A3B")));
        List<String> tokens = new ArrayList<>();
        StreamingReplyHandler handler = new StreamingReplyHandler() {
            @Override public void onPartialResponse(String token) { tokens.add(token); }
            @Override public void onCompleteResponse(String fullReply, int tokens) { }
            @Override public void onError(Throwable error) { }
        };

        router.stream(new LlmRequest("siliconflow-large", "hi", 1024), handler);

        // 委托到 route executor.stream + 翻成 provider raw 模型串（非合成 id）
        assertThat(siliconflow.received).hasSize(1);
        assertThat(siliconflow.received.get(0).modelId()).isEqualTo("Qwen/Qwen3.5-35B-A3B");
        // handler 回调透传（路由层不吞 token）
        assertThat(tokens).containsExactly("tok-1-siliconflow", "tok-2-siliconflow");
    }

    @Test
    void stream_unregisteredModelIdThrowsSelectionException() {
        RoutingModelExecutor router = new RoutingModelExecutor(Map.of());
        StreamingReplyHandler noop = new StreamingReplyHandler() {
            @Override public void onPartialResponse(String token) { }
            @Override public void onCompleteResponse(String fullReply, int tokens) { }
            @Override public void onError(Throwable error) { }
        };
        assertThatThrownBy(() -> router.stream(new LlmRequest("nobody", "hi", 1024), noop))
                .isInstanceOf(ModelSelectionException.class);
    }
}
