package com.agentdemo007.admin;

import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.config.ModelMetadata.ModelStatus;
import com.agentdemo007.gateway.registry.ModelRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理台·模型配置查询端点单测（Phase 19·T96）。
 *
 * <p>{@code GET /admin/models}：只读 {@link ModelConfigCenter#registry()} 全量快照，经
 * {@link ModelSummary} 强类型透出——<b>刻意剔除 apiKey</b>（密钥不外泄，④收口 + 安全）。
 * 权重为热生效后的当前值（与 {@code applyWeight} 联动）。
 *
 * <p>直构控制器（同 {@code ChatControllerTest} 约定），断言 {@link UnifiedResponse}；序列化安全守卫
 * 断言 JSON 不含 {@code apiKey}/密钥明文。
 */
class AdminModelControllerTest {

    private final ModelRegistry registry = new ModelRegistry();
    private final ModelConfigCenter configCenter = new ModelConfigCenter(() -> null, registry);
    private final AdminModelController controller = new AdminModelController(configCenter);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void models_returnsAllSummariesWithCurrentWeightAndStatus() {
        registry.register(ModelMetadata.builder("m1").name("GPT-4").provider("openai")
                .endpoint("https://api.openai.com").apiKey("sk-secret-123").weight(5)
                .tags(java.util.Set.of("chat", "tool"))
                .maxTokens(8192).costPer1KTokens(0.03).build());
        registry.register(ModelMetadata.builder("m2").status(ModelStatus.DISABLED).weight(0).build());

        UnifiedResponse resp = controller.models();

        assertThat(resp.code()).isEqualTo(0);
        List<ModelSummary> data = asList(resp);
        assertThat(data).hasSize(2);
        ModelSummary m1 = data.stream().filter(m -> m.id().equals("m1")).findFirst().orElseThrow();
        assertThat(m1.weight()).isEqualTo(5);
        assertThat(m1.enabled()).isTrue();
        assertThat(m1.status()).isEqualTo("ENABLED");
        assertThat(m1.provider()).isEqualTo("openai");
        assertThat(m1.endpoint()).isEqualTo("https://api.openai.com");
        assertThat(m1.tags()).containsExactlyInAnyOrder("chat", "tool");
        ModelSummary m2 = data.stream().filter(m -> m.id().equals("m2")).findFirst().orElseThrow();
        assertThat(m2.enabled()).isFalse();
        assertThat(m2.status()).isEqualTo("DISABLED");
    }

    @Test
    void models_neverLeaksApiKey_inSerializedJson() throws Exception {
        registry.register(ModelMetadata.builder("m1").apiKey("sk-super-secret").weight(1).build());

        UnifiedResponse resp = controller.models();
        String json = objectMapper.writeValueAsString(resp.data());

        assertThat(json).doesNotContain("apiKey");
        assertThat(json).doesNotContain("sk-super-secret");
    }

    @Test
    void models_reflectsLiveWeightAfterApplyWeight() {
        registry.register(ModelMetadata.builder("m1").weight(5).build());
        configCenter.applyWeight("m1", 9);

        List<ModelSummary> data = asList(controller.models());

        assertThat(data).hasSize(1);
        assertThat(data.get(0).weight()).isEqualTo(9);
    }

    @Test
    void models_emptyRegistry_returnsEmptyList() {
        UnifiedResponse resp = controller.models();

        assertThat(resp.code()).isEqualTo(0);
        assertThat(asList(resp)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<ModelSummary> asList(UnifiedResponse resp) {
        return (List<ModelSummary>) resp.data();
    }
}
