package com.agentdemo007.admin;

import com.agentdemo007.common.response.ErrorCode;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.config.ConfigWriter;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelConfigSnapshot;
import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.registry.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维控制台·路由权重动态调权测试（Phase 15·T66）。
 *
 * <p>{@link RouteWeightController} 收口"动态调整路由权重 → 写回 Nacos 热加载生效"：
 * ① 校验入参（modelId 非空、weight 非负，违例 400）；
 * ② 命 {@link ModelConfigCenter#applyWeight} 就地热生效（注册表立见新权重）；
 * ③ 经 {@link ConfigWriter} 写回配置中心（best-effort，②降级：写回失败不回滚内存、不抛）；
 * ④ 未知模型 404。
 *
 * <p>单测以真实 {@link ModelConfigCenter}（lambda 源）+ 记录型 {@link ConfigWriter} 构造，
 * 验证调权后内存热生效、写回被调用、降级路径不抛且 persisted=false。
 */
class RouteWeightControllerTest {

    private ModelConfigCenter freshCenter(ModelMetadata... models) {
        ModelConfigCenter center = new ModelConfigCenter(
                () -> new ModelConfigSnapshot(List.of(models), List.of(), null, null), new ModelRegistry());
        center.refresh();
        return center;
    }

    @Test
    void adjust_appliesInMemoryAndWritesBack_returnsAppliedAndPersisted() {
        ModelConfigCenter center = freshCenter(ModelMetadata.builder("m1").weight(1).build());
        List<String> captured = new ArrayList<>();
        ConfigWriter writer = (id, w) -> { captured.add(id + ":" + w); return true; };
        RouteWeightController controller = new RouteWeightController(center, writer);

        UnifiedResponse response = controller.adjust(new RouteWeightRequest("m1", 7));

        assertThat(response.code()).isEqualTo(ErrorCode.SUCCESS.code());
        RouteWeightResponse data = (RouteWeightResponse) response.data();
        assertThat(data.modelId()).isEqualTo("m1");
        assertThat(data.weight()).isEqualTo(7);
        assertThat(data.applied()).isTrue();
        assertThat(data.persisted()).isTrue(); // 写回成功
        // 内存热生效：注册表已见新权重
        assertThat(center.registry().get("m1").orElseThrow().weight()).isEqualTo(7);
        assertThat(captured).containsExactly("m1:7"); // 写回被调用且参数正确
    }

    @Test
    void adjust_modelNotFound_returns404_doesNotWriteBack() {
        ModelConfigCenter center = freshCenter(ModelMetadata.builder("m1").build());
        List<String> captured = new ArrayList<>();
        ConfigWriter writer = (id, w) -> { captured.add(id); return true; };
        RouteWeightController controller = new RouteWeightController(center, writer);

        UnifiedResponse response = controller.adjust(new RouteWeightRequest("nope", 5));

        assertThat(response.code()).isEqualTo(ErrorCode.NOT_FOUND.code());
        assertThat(response.data()).isNull();
        assertThat(captured).isEmpty(); // 未命中 → 不写回
    }

    @Test
    void adjust_blankModelIdOrNegativeWeight_returns400() {
        ModelConfigCenter center = freshCenter(ModelMetadata.builder("m1").build());
        ConfigWriter writer = (id, w) -> true;
        RouteWeightController controller = new RouteWeightController(center, writer);

        assertThat(controller.adjust(new RouteWeightRequest("", 5)).code())
                .isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(controller.adjust(new RouteWeightRequest(null, 5)).code())
                .isEqualTo(ErrorCode.BAD_REQUEST.code());
        assertThat(controller.adjust(new RouteWeightRequest("m1", -1)).code())
                .isEqualTo(ErrorCode.BAD_REQUEST.code());
    }

    @Test
    void adjust_writeBackThrowsStillAppliesInMemory_persistedFalse() {
        ModelConfigCenter center = freshCenter(ModelMetadata.builder("m1").weight(1).build());
        // 写回抛异常（模拟 Nacos 不可达）——②降级：不回滚内存、不抛
        ConfigWriter throwing = (id, w) -> { throw new RuntimeException("Nacos 不可达"); };
        RouteWeightController controller = new RouteWeightController(center, throwing);

        UnifiedResponse response = controller.adjust(new RouteWeightRequest("m1", 9));

        assertThat(response.code()).isEqualTo(ErrorCode.SUCCESS.code()); // 不抛、不 5xx
        RouteWeightResponse data = (RouteWeightResponse) response.data();
        assertThat(data.applied()).isTrue();
        assertThat(data.persisted()).isFalse(); // 写回失败
        // 内存仍热生效（写回失败不回滚）
        assertThat(center.registry().get("m1").orElseThrow().weight()).isEqualTo(9);
    }
}
