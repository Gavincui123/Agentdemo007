package com.agentdemo007.intent;

import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelConfigSnapshot;
import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.gateway.exception.ModelSelectionException;
import com.agentdemo007.gateway.registry.ModelRegistry;
import com.agentdemo007.gateway.selector.TagBasedSelector;
import com.agentdemo007.observability.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.agentdemo007.gateway.config.RouteRule.RouteType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型路由测试（第三层·对接 ModelSelector 选模型，§5.3.3/§5.3.4）。
 *
 * <p>{@link ModelRouter}：有路由规则且规则有 targetModelId → 直接用规则目标；
 * 否则在注册表中按 {@link RouteType} 标签筛候选、经选择策略选一个；无候选 →
 * {@link ModelSelectionException}（由 {@code RouteDispatchStep} 收口为 MODEL_DOWN 话术短路）。
 */
class ModelRouterTest {

    private ModelConfigCenter centerWith(List<ModelMetadata> models, List<RouteRule> rules) {
        ModelRegistry registry = new ModelRegistry();
        ModelConfigCenter center = new ModelConfigCenter(() ->
                new ModelConfigSnapshot(models, rules, null, null), registry);
        center.refresh();
        return center;
    }

    @Test
    void ruleWithTargetModel_returnsTarget() {
        ModelConfigCenter center = centerWith(List.of(),
                List.of(new RouteRule("r1", Intent.REASONING, RouteType.REASONING, "m-reason")));
        ModelRouter router = new ModelRouter(center, new TagBasedSelector());

        assertThat(router.route(Intent.REASONING, RouteType.REASONING)).isEqualTo("m-reason");
    }

    @Test
    void noRule_selectsFromTaggedCandidates() {
        ModelMetadata simple = ModelMetadata.builder("m-simple")
                .tags(Set.of("SIMPLE")).build();
        ModelConfigCenter center = centerWith(List.of(simple), List.of());
        ModelRouter router = new ModelRouter(center, new TagBasedSelector());

        assertThat(router.route(Intent.CHIT_CHAT, RouteType.SIMPLE)).isEqualTo("m-simple");
    }

    @Test
    void noCandidates_throwsModelSelection() {
        ModelConfigCenter center = centerWith(List.of(), List.of());
        ModelRouter router = new ModelRouter(center, new TagBasedSelector());

        assertThatThrownBy(() -> router.route(Intent.OTHER, RouteType.SIMPLE))
                .isInstanceOf(ModelSelectionException.class);
    }

    @Test
    void route_recordsRouteCounterTaggedByRouteAndModel() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        ModelConfigCenter center = centerWith(List.of(),
                List.of(new RouteRule("r1", Intent.REASONING, RouteType.REASONING, "m-reason")));
        ModelRouter router = new ModelRouter(center, new TagBasedSelector(), metrics);

        String modelId = router.route(Intent.REASONING, RouteType.REASONING);

        assertThat(modelId).isEqualTo("m-reason");
        assertThat(registry.counter("agent.route", "route", "REASONING", "model", "m-reason").count())
                .isEqualTo(1.0);
    }
}
