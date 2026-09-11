package com.agentdemo007.output;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 结构化输出装配（Phase 12·第七层 Spring 配置）。
 *
 * <p>为 {@link OutputStep}（{@code @Component} 自扫描）提供协作者 bean：
 * {@link JsonSchemaValidator}→{@link OutputRetryFallback}（受 {@code app.output.max-retries} 限制）
 * →{@link StructuredOutputGateway}（+ {@link DegradationPhraseCenter} 兜底话术）、{@link OutputSecurityFilter}。
 *
 * <p>两个引擎无关 seam 落 dev 默认、由 {@code @ConditionalOnMissingBean} 供 prod 覆盖：
 * <ul>
 *   <li>{@link OutputSchemaResolver} — dev {@link OutputSchemaResolver#lenient()}（对话模式不校验结构），
 *       prod 覆盖为意图→schema 映射（随真实结构化场景接入）；</li>
 *   <li>{@link ReAsk} — dev {@link ReAsk#none()}（无 LLM 重问→失败即耗尽兜底），
 *       prod 覆盖为经 {@code ChatLlmService} 以错误反馈再问。</li>
 * </ul>
 */
@Configuration
public class OutputConfig {

    @Bean
    JsonSchemaValidator jsonSchemaValidator() {
        return new JsonSchemaValidator();
    }

    @Bean
    OutputRetryFallback outputRetryFallback(JsonSchemaValidator validator,
                                            @Value("${app.output.max-retries:2}") int maxRetries) {
        return new OutputRetryFallback(validator, maxRetries);
    }

    @Bean
    StructuredOutputGateway structuredOutputGateway(OutputRetryFallback retryFallback,
                                                     DegradationPhraseCenter phraseCenter) {
        return new StructuredOutputGateway(retryFallback, phraseCenter);
    }

    @Bean
    OutputSecurityFilter outputSecurityFilter() {
        return new OutputSecurityFilter();
    }

    @Bean
    @ConditionalOnMissingBean(OutputSchemaResolver.class)
    OutputSchemaResolver outputSchemaResolver() {
        return OutputSchemaResolver.lenient();
    }

    @Bean
    @ConditionalOnMissingBean(ReAsk.class)
    ReAsk reAsk() {
        return ReAsk.none();
    }
}
