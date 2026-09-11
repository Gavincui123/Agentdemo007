package com.agentdemo007.feedback;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;

/**
 * 微调闭环·数据层装配（Phase 15·T67）。
 *
 * <p>{@link FeedbackCollector} 用系统时钟（{@code OffsetDateTime::now}）；单测经 2-arg 构造注入确定性时钟。
 * {@link InMemoryOfflineDataPool} 由 {@code @Component} 自动装配（dev 占位，prod 可 {@code @ConditionalOnMissingBean}
 * 覆盖为 JPA/对象存储实现）。
 */
@Configuration
public class FeedbackConfig {

    @Bean
    FeedbackCollector feedbackCollector(OfflineDataPool pool) {
        return new FeedbackCollector(pool, OffsetDateTime::now);
    }
}
