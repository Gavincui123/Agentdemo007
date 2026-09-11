package com.agentdemo007.gateway.config;

import com.agentdemo007.gateway.registry.ModelRegistry;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.agentdemo007.gateway.config.RouteRule.RouteType;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型配置中心测试（Phase 4·热加载落地 + 查询收口）。
 *
 * <p>{@link ModelConfigCenter} 从 {@link ModelConfigSource} 加载快照 → 清空并重建
 * {@link ModelRegistry}，对外提供路由规则/流控/容灾查询。源可插拔（dev 本地 / prod Nacos），
 * 测试用 lambda 源断言热加载与查询。
 */
class ModelConfigCenterTest {

    @Test
    void refresh_populatesRegistryAndExposesQueries() {
        ModelConfigSnapshot snapshot = new ModelConfigSnapshot(
                List.of(ModelMetadata.builder("m1").build(), ModelMetadata.builder("m2").build()),
                List.of(new RouteRule("r1", Intent.REASONING, RouteType.REASONING, "m1")),
                new FlowControlPolicy("fc", 100, 100000, Duration.ofSeconds(1)),
                new FailoverPolicy.Builder("fo").maxRetries(2).fallbackModelIds(List.of("m2")).build());
        ModelConfigCenter center = new ModelConfigCenter(() -> snapshot, new ModelRegistry());

        center.refresh();

        assertThat(center.registry().all()).hasSize(2);
        assertThat(center.routeFor(Intent.REASONING)).hasValueSatisfying(r ->
                assertThat(r.targetModelId()).isEqualTo("m1"));
        assertThat(center.flowControl().maxRequestsPerSecond()).isEqualTo(100);
        assertThat(center.failover().fallbackModelIds()).contains("m2");
    }

    @Test
    void refresh_replacesNotAppends() {
        ModelRegistry registry = new ModelRegistry();
        ModelConfigCenter center = new ModelConfigCenter(() -> new ModelConfigSnapshot(
                List.of(ModelMetadata.builder("only").build()),
                List.of(), null, null), registry);

        registry.register(ModelMetadata.builder("stale").build());
        center.refresh();

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.get("stale")).isEmpty();
        assertThat(registry.get("only")).isPresent();
    }

    @Test
    void routeFor_unknownIntent_returnsEmpty() {
        ModelConfigCenter center = new ModelConfigCenter(() -> new ModelConfigSnapshot(
                List.of(), List.of(new RouteRule("r", Intent.REASONING, RouteType.REASONING, "m")),
                null, null), new ModelRegistry());

        center.refresh();

        assertThat(center.routeFor(Intent.CHIT_CHAT)).isEmpty();
    }

    @Test
    void flowControl_beforeRefresh_isNull() {
        ModelConfigCenter center = new ModelConfigCenter(() -> new ModelConfigSnapshot(
                List.of(), List.of(), null, null), new ModelRegistry());

        assertThat(center.flowControl()).isNull();
        assertThat(center.failover()).isNull();
    }

    @Test
    void applyWeight_updatesModelWeightInRegistryHot_otherFieldsPreserved() {
        ModelMetadata m1 = ModelMetadata.builder("m1")
                .name("Model One")
                .provider("p")
                .endpoint("http://x")
                .tags(Set.of("REASONING"))
                .weight(1)
                .costPer1KTokens(0.5)
                .build();
        ModelConfigCenter center = new ModelConfigCenter(
                () -> new ModelConfigSnapshot(List.of(m1), List.of(), null, null), new ModelRegistry());
        center.refresh();

        boolean applied = center.applyWeight("m1", 9);

        assertThat(applied).isTrue();
        ModelMetadata updated = center.registry().get("m1").orElseThrow();
        assertThat(updated.weight()).isEqualTo(9); // 热生效：注册表已见新权重
        // 收口：仅调权，不重建整个模型——其他字段保持
        assertThat(updated.name()).isEqualTo("Model One");
        assertThat(updated.provider()).isEqualTo("p");
        assertThat(updated.tags()).contains("REASONING");
        assertThat(updated.costPer1KTokens()).isEqualTo(0.5);
    }

    @Test
    void applyWeight_unknownModel_returnsFalse_doesNotMutate() {
        ModelConfigCenter center = new ModelConfigCenter(
                () -> new ModelConfigSnapshot(List.of(ModelMetadata.builder("m1").build()),
                        List.of(), null, null), new ModelRegistry());
        center.refresh();

        assertThat(center.applyWeight("nope", 5)).isFalse(); // ②降级：未知模型不抛
        assertThat(center.registry().all()).hasSize(1);
    }

    @Test
    void registerModel_addsToRegistry_routeSelectable() {
        ModelConfigCenter center = new ModelConfigCenter(
                () -> new ModelConfigSnapshot(List.of(), List.of(), null, null), new ModelRegistry());
        center.refresh();
        ModelMetadata newModel = ModelMetadata.builder("fine-tuned-1")
                .provider("ft")
                .endpoint("http://ft")
                .tags(Set.of("REASONING"))
                .weight(5)
                .build();

        center.registerModel(newModel);

        // 内存热注册：立即可被路由选中（registry 已见，byTag 命中）
        ModelMetadata registered = center.registry().get("fine-tuned-1").orElseThrow();
        assertThat(registered.endpoint()).isEqualTo("http://ft");
        assertThat(registered.isEnabled()).isTrue();
        assertThat(center.registry().byTag("REASONING")).contains(registered);
    }
}
