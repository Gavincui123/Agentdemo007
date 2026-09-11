package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
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
}
