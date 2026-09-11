package com.agentdemo007.gateway.registry;

import com.agentdemo007.gateway.config.ModelMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.agentdemo007.gateway.config.ModelMetadata.ModelStatus.DISABLED;
import static com.agentdemo007.gateway.config.ModelMetadata.ModelStatus.ENABLED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型注册表测试（Phase 4·动态注册/查询的数据真相源）。
 *
 * <p>注册表是 {@code ModelConfigCenter} 热加载落地的载体，也是 {@code ModelSelector} 的候选来源。
 * 收口：所有模型查询只经此表，不自造散落的模型集合。
 */
class ModelRegistryTest {

    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
    }

    @Test
    void registerAndGet_byId() {
        ModelMetadata m = ModelMetadata.builder("m1").build();

        registry.register(m);

        assertThat(registry.get("m1")).contains(m);
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void register_replacesSameId() {
        registry.register(ModelMetadata.builder("m1").weight(1).build());
        registry.register(ModelMetadata.builder("m1").weight(9).build());

        assertThat(registry.get("m1").orElseThrow().weight()).isEqualTo(9);
        assertThat(registry.all()).hasSize(1); // 不重复
    }

    @Test
    void unregister_removes() {
        registry.register(ModelMetadata.builder("m1").build());

        registry.unregister("m1");

        assertThat(registry.get("m1")).isEmpty();
    }

    @Test
    void get_unknownId_returnsEmpty() {
        assertThat(registry.get("nope")).isEmpty();
    }

    @Test
    void enabled_returnsOnlyEnabled() {
        registry.register(ModelMetadata.builder("on").status(ENABLED).build());
        registry.register(ModelMetadata.builder("off").status(DISABLED).build());

        assertThat(registry.enabled()).hasSize(1);
        assertThat(registry.enabled().get(0).id()).isEqualTo("on");
    }

    @Test
    void byTag_filtersByTag() {
        registry.register(ModelMetadata.builder("a").tags(Set.of("推理")).build());
        registry.register(ModelMetadata.builder("b").tags(Set.of("闲聊")).build());

        assertThat(registry.byTag("推理")).hasSize(1);
        assertThat(registry.byTag("推理").get(0).id()).isEqualTo("a");
    }

    @Test
    void clear_removesAll() {
        registry.register(ModelMetadata.builder("a").build());
        registry.register(ModelMetadata.builder("b").build());

        registry.clear();

        assertThat(registry.all()).isEmpty();
    }
}
