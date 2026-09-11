package com.agentdemo007.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微调闭环·训练+注册装配（Phase 15·T68）。
 *
 * <p>dev 默认装配占位 seam：{@link TrainingJobRunner#NO_OP}（不触达训练平台）、
 * {@link RegistrationWriter#NO_OP}（不写回配置中心）——热生效走 {@link ModelConfigCenter#registerModel}
 * 内存路径兜底。{@link ModelRegistrar} 为 {@code @Component} 自动装配；{@link FineTuningPipeline} 经
 * @Bean 工厂注入三依赖。prod 用 {@code @ConditionalOnMissingBean} 覆盖为真实训练适配器 + Nacos 写回。
 */
@Configuration
public class FineTuneConfig {

    private static final Logger log = LoggerFactory.getLogger(FineTuneConfig.class);

    @Bean
    @ConditionalOnMissingBean(TrainingJobRunner.class)
    TrainingJobRunner trainingJobRunner() {
        log.info("未配置 TrainingJobRunner，使用 NO_OP（dev；prod 应装配外部训练平台适配器）");
        return TrainingJobRunner.NO_OP;
    }

    @Bean
    @ConditionalOnMissingBean(RegistrationWriter.class)
    RegistrationWriter registrationWriter() {
        log.info("未配置 RegistrationWriter，使用 NO_OP（dev；prod 应装配 NacosConfigWriter 写回模型注册）");
        return RegistrationWriter.NO_OP;
    }

    @Bean
    FineTuningPipeline fineTuningPipeline(OfflineDataPool pool, TrainingJobRunner runner, ModelRegistrar registrar) {
        return new FineTuningPipeline(pool, runner, registrar);
    }
}
