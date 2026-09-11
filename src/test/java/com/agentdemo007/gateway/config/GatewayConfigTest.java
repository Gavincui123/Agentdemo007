package com.agentdemo007.gateway.config;

import com.agentdemo007.gateway.registry.ModelRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型网关装配单测（Phase 12·dev 占位模型种子）。
 *
 * <p>验证 dev {@link ModelConfigSource} 产出的快照含一个启用模型——保证无真实 LLM/Nacos
 * 也可经 {@link com.agentdemo007.gateway.llm.ChatLlmService} 选到 {@code NoopModelExecutor}，
 * 全链路在 dev 环境可跑通（每步降级：程序始终运行）。
 */
class GatewayConfigTest {

    @Test
    void devSnapshot_containsOneEnabledModel() {
        ModelConfigSnapshot snapshot = GatewayConfig.devSnapshot();

        assertThat(snapshot.models()).hasSize(1);
        assertThat(snapshot.models().get(0).isEnabled()).isTrue();
    }

    @Test
    void devSnapshot_loadsEnabledModelIntoRegistry() {
        ModelConfigSource source = () -> GatewayConfig.devSnapshot();
        ModelConfigCenter center = new ModelConfigCenter(source, new ModelRegistry());

        center.refresh();

        assertThat(center.registry().enabled()).hasSize(1);
    }
}
