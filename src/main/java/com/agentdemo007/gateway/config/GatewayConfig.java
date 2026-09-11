package com.agentdemo007.gateway.config;

import com.agentdemo007.gateway.core.FailoverExecutor;
import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.TokenBudgetChecker;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import com.agentdemo007.gateway.registry.ModelRegistry;
import com.agentdemo007.gateway.selector.CostAwareSelector;
import com.agentdemo007.gateway.selector.ModelSelector;
import com.agentdemo007.gateway.selector.TagBasedSelector;
import com.agentdemo007.gateway.selector.WeightBasedSelector;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.resilience.BackoffStrategy;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.ResilientExecutor;
import com.agentdemo007.resilience.RetryPolicy;
import com.agentdemo007.resilience.Sleeper;
import com.agentdemo007.prompt.LocalPromptSource;
import com.agentdemo007.prompt.PromptRegistry;
import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.gateway.llm.ChatLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 模型网关 Spring 装配（Phase 4）。
 *
 * <p>注册网关内核 bean（注册表/配置中心/预算关卡/故障转移/统一网关）与三种选择策略。
 * dev 环境提供 {@link NoopModelExecutor}（返回占位响应，每步降级原则）与空配置源，
 * 保证无真实 LLM/Nacos 也可启动；prod 用 {@code @ConditionalOnMissingBean} 覆盖为
 * LangChain4j 执行器与 Nacos 配置源。
 */
@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    @Bean
    ModelRegistry modelRegistry() {
        return new ModelRegistry();
    }

    @Bean
    @ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "false", matchIfMissing = true)
    ModelConfigSource modelConfigSource() {
        log.info("llm.enabled=false/缺省：使用 dev 占位模型源（noop；llm.enabled=true 时由 LlmConfig 装配真源）");
        return () -> devSnapshot();
    }

    /**
     * dev 占位模型快照：单个启用模型 {@code dev-noop}，无路由/流控/容灾策略。
     *
     * <p>选模型时无路由规则 → {@link TagBasedSelector} 在启用模型中选 → 命中 {@code dev-noop}
     * → {@link NoopModelExecutor} 返回占位回复。null 流控/容灾分别由 {@link TokenBudgetChecker}
     * （空策略直通）与 {@link ChatLlmService}（failover 缺省自建）兜底，全链路在 dev 可跑通。
     */
    static ModelConfigSnapshot devSnapshot() {
        ModelMetadata noop = ModelMetadata.builder("dev-noop")
                .name("dev-noop")
                .provider("dev")
                .build();
        return new ModelConfigSnapshot(List.of(noop), List.of(), null, null);
    }

    @Bean
    ModelConfigCenter modelConfigCenter(ModelConfigSource source, ModelRegistry registry) {
        return new ModelConfigCenter(source, registry);
    }

    // ---- 提示词注册中心：dev 本地源（prod 由 NacosPromptSource + AiService 覆盖，待鉴权/真实 Nacos 接入） ----

    @Bean
    @ConditionalOnMissingBean(PromptRegistry.class)
    PromptRegistry promptRegistry() {
        log.info("未配置提示词源，使用本地内存源（dev；prod 应装配 NacosPromptSource）");
        return new LocalPromptSource();
    }

    @Bean
    TokenBudgetChecker tokenBudgetChecker() {
        return new TokenBudgetChecker();
    }

    // ---- Phase 5 韧性层：重试 + 分诊 + 退避，注入 FailoverExecutor（重试=同模型退避，耗尽=切备选） ----

    @Bean
    BackoffStrategy backoffStrategy() {
        return new BackoffStrategy();
    }

    @Bean
    ExceptionTriage exceptionTriage() {
        return new ExceptionTriage();
    }

    @Bean
    RetryPolicy retryPolicy() {
        return RetryPolicy.defaults();
    }

    @Bean
    ResilientExecutor resilientExecutor(ExceptionTriage triage, BackoffStrategy backoff) {
        Sleeper sleeper = millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        };
        return new ResilientExecutor(triage, backoff, sleeper, new Random());
    }

    @Bean
    FailoverExecutor failoverExecutor(ResilientExecutor resilientExecutor, RetryPolicy retryPolicy,
                                      ExceptionTriage triage, AgentMetrics metrics) {
        return new FailoverExecutor(resilientExecutor, retryPolicy, triage, metrics);
    }

    // ---- LLM 收口入口 + 注入隔离器（Phase 6 会话理解层辅助调用：摘要/改写/分类经此出站） ----

    @Bean
    PromptSanitizer promptSanitizer() {
        return new PromptSanitizer();
    }

    @Bean
    ChatLlmService chatLlmService(UnifiedModelGateway gateway, ModelConfigCenter center,
                                  ModelSelector selector, PromptSanitizer sanitizer,
                                  @Value("${llm.thinking.enabled:true}") boolean thinkingEnabled,
                                  @Value("${llm.max-tokens:1024}") int maxTokens) {
        return new ChatLlmService(gateway, center, selector, sanitizer, thinkingEnabled, maxTokens);
    }

    @Bean
    @ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "false", matchIfMissing = true)
    ModelExecutor noopModelExecutor() {
        log.warn("llm.enabled=false/缺省：使用 Noop 占位执行器（dev 降级；llm.enabled=true 时由 LlmConfig 装配真实路由+熔断执行器）");
        return new NoopModelExecutor();
    }

    @Bean
    UnifiedModelGateway unifiedModelGateway(ModelExecutor executor, TokenBudgetChecker budget,
                                            FailoverExecutor failover) {
        return new UnifiedModelGateway(executor, budget, failover);
    }

    // ---- 模型选择策略（@Primary 单 bean，app.model-selector.strategy 属性可配置切换） ----

    @Bean
    @Primary
    ModelSelector modelSelector(@Value("${app.model-selector.strategy:tag}") String strategy) {
        return selectModelSelector(strategy);
    }

    /**
     * 策略选择工厂（可单测）：{@code tag}/{@code weight}/{@code cost} 三态，
     * 未知/null 归一为 {@code tag}（安全默认）。由 {@code app.model-selector.strategy} 属性驱动。
     */
    static ModelSelector selectModelSelector(String strategy) {
        String key = (strategy == null) ? "" : strategy.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "weight" -> new WeightBasedSelector();
            case "cost" -> new CostAwareSelector();
            default -> new TagBasedSelector();
        };
    }

    /** dev 占位执行器：不调用真实引擎，返回降级占位响应。 */
    static class NoopModelExecutor implements ModelExecutor {
        @Override
        public LlmResponse execute(LlmRequest request) {
            return new LlmResponse(request.modelId(),
                    "[dev noop] 模型执行器未配置，这是占位回复。", 1);
        }
    }
}
