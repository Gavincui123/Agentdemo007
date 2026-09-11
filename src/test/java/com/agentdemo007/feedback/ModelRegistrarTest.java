package com.agentdemo007.feedback;

import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.ModelConfigSnapshot;
import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.registry.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 模型注册器测试（Phase 15·T68 微调闭环·训练+注册）。
 *
 * <p>{@link ModelRegistrar} 把训练产出的 {@link TrainedModel} 注册到 {@link ModelConfigCenter}
 * （内存热生效，立即可被路由选中）+ 经 {@link RegistrationWriter} 写回配置中心（best-effort，
 * ②降级：写回失败不回滚内存、不抛）。满足验收"注册新模型→配置中心热加载→可被路由选中"。
 */
class ModelRegistrarTest {

    private ModelConfigCenter emptyCenter() {
        ModelConfigCenter center = new ModelConfigCenter(
                () -> new ModelConfigSnapshot(List.of(), List.of(), null, null), new ModelRegistry());
        center.refresh();
        return center;
    }

    private TrainedModel trained() {
        return new TrainedModel("ft-1", "ft-provider", "http://ft",
                Set.of("REASONING"), 5);
    }

    @Test
    void register_appliesInMemoryAndWritesBack_returnsTrue() {
        ModelConfigCenter center = emptyCenter();
        List<TrainedModel> captured = new ArrayList<>();
        RegistrationWriter writer = m -> { captured.add(m); return true; };
        ModelRegistrar registrar = new ModelRegistrar(center, writer);

        boolean persisted = registrar.register(trained());

        assertThat(persisted).isTrue(); // 写回成功
        assertThat(captured).containsExactly(trained()); // 写回被调用且参数正确
        // 内存热生效：注册表已见新模型且可被路由选中
        ModelMetadata registered = center.registry().get("ft-1").orElseThrow();
        assertThat(registered.endpoint()).isEqualTo("http://ft");
        assertThat(registered.isEnabled()).isTrue();
        assertThat(center.registry().byTag("REASONING")).contains(registered);
    }

    @Test
    void register_writeBackThrowsStillAppliesInMemory_returnsFalse() {
        ModelConfigCenter center = emptyCenter();
        // 写回抛异常（模拟配置中心不可达）——②降级：不回滚内存、不抛
        RegistrationWriter throwing = m -> { throw new RuntimeException("配置中心不可达"); };
        ModelRegistrar registrar = new ModelRegistrar(center, throwing);

        assertThatCode(() -> registrar.register(trained()))
                .doesNotThrowAnyException();
        boolean persisted = registrar.register(trained());
        assertThat(persisted).isFalse(); // 写回失败
        // 内存仍热生效（写回失败不回滚）
        assertThat(center.registry().get("ft-1")).isPresent();
    }
}
