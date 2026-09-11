package com.agentdemo007.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置中心写回 seam 装配（Phase 15·T66 dev 兜底）。
 *
 * <p>补齐 {@link ConfigWriter} 的 dev 默认 {@link ConfigWriter#NO_OP} bean——设计上与
 * {@code RegistrationWriter}/{@code TrainingJobRunner} 的 {@code @ConditionalOnMissingBean} NO_OP
 * 兜底同形（prod 装配 {@code NacosConfigWriter} 覆盖），但 T66 装配时漏配，致使 {@code RouteWeightController}
 * 在 {@code @SpringBootTest} 全上下文中无 bean 可注入、上下文启动失败。本类补齐该 dev 兜底。
 *
 * <p>dev 下 {@link ConfigWriter#NO_OP} 返回 false（不持久化），"热生效"由
 * {@code ModelConfigCenter.applyWeight} 的内存路径兜底（②每步降级）。
 */
@Configuration
public class ConfigWriterConfig {

    @Bean
    @ConditionalOnMissingBean(ConfigWriter.class)
    ConfigWriter configWriter() {
        return ConfigWriter.NO_OP;
    }
}
