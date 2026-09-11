package com.agentdemo007.context;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.prompt.PromptRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 上下文构建工厂 Spring 装配（Phase 8）。
 *
 * <p>注册三层构建器 + 拼接器为 bean（协作对象模式：bean 装配；{@link ContextBuilder}
 * 作为 {@code @Component} 步骤由 {@code PipelineOrchestrator} 按 {@code @Order} 自动收集）。
 * 复用 {@link PromptRegistry}（{@code GatewayConfig} 本地源，prod 由 Nacos 覆盖）与
 * {@link PromptSanitizer}；{@link Clock} 提供生产系统时钟（测试注入固定时钟保证确定性）。
 */
@Configuration
public class ContextConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    SystemAnchorLayer systemAnchorLayer(PromptRegistry registry, Clock clock) {
        return new SystemAnchorLayer(registry, clock);
    }

    @Bean
    ObjectiveDataLayer objectiveDataLayer() {
        return new ObjectiveDataLayer();
    }

    @Bean
    UserInstructionLayer userInstructionLayer(PromptSanitizer sanitizer) {
        return new UserInstructionLayer(sanitizer);
    }

    @Bean
    ContextMerger contextMerger(SystemAnchorLayer systemAnchorLayer,
                               ObjectiveDataLayer objectiveDataLayer,
                               UserInstructionLayer userInstructionLayer) {
        return new ContextMerger(systemAnchorLayer, objectiveDataLayer, userInstructionLayer);
    }
}
