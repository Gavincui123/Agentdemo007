package com.agentdemo007.gateway.config;

import com.agentdemo007.gateway.registry.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 模型配置启动加载器单测（Phase 12·启动即 refresh）。
 *
 * <p>验证 {@link ModelConfigBootstrap} 在启动完成后触发 {@link ModelConfigCenter#refresh()}，
 * 把 {@link ModelConfigSource} 快照落地到注册表；源异常时不阻塞启动（每步降级，
 * 注册表维持空、下游网关收口为 MODEL_DOWN 话术）。
 */
class ModelConfigBootstrapTest {

    @Test
    void run_triggersRefresh_populatesEnabledModels() {
        ModelConfigSource source = () -> new ModelConfigSnapshot(
                List.of(ModelMetadata.builder("dev-noop").build()), List.of(), null, null);
        ModelConfigCenter center = new ModelConfigCenter(source, new ModelRegistry());
        ModelConfigBootstrap bootstrap = new ModelConfigBootstrap(center);

        bootstrap.run();

        assertThat(center.registry().enabled()).hasSize(1);
    }

    @Test
    void run_sourceThrows_doesNotBlockStartup() {
        ModelConfigSource source = () -> {
            throw new RuntimeException("nacos down");
        };
        ModelConfigCenter center = new ModelConfigCenter(source, new ModelRegistry());
        ModelConfigBootstrap bootstrap = new ModelConfigBootstrap(center);

        // 每步降级：不抛异常、不阻塞启动
        assertThatCode(() -> bootstrap.run()).doesNotThrowAnyException();
        assertThat(center.registry().enabled()).isEmpty();
    }
}
