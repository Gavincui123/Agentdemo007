package com.agentdemo007.gateway.config;

import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.agentdemo007.gateway.config.FailoverPolicy.FailoverStrategy;
import static com.agentdemo007.gateway.config.RouteRule.RouteType;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型治理配置实体测试（Phase 4·纯数据载体契约）。
 *
 * <p>验证构造/API 与关键默认值：元数据、路由规则、流控策略、容灾策略均可正确装配，
 * 为 {@code ModelRegistry}/{@code ModelSelector}/{@code ModelConfigCenter}/{@code GatewayInterceptor} 提供单一数据真相源。
 */
class GatewayConfigEntitiesTest {

    @Test
    void modelMetadata_buildsAndExposesFields() {
        ModelMetadata meta = new ModelMetadata.Builder("openai:gpt-4o")
                .provider("openai")
                .endpoint("https://api.example.com/v1/chat")
                .apiKey("***")
                .maxTokens(8192)
                .timeout(Duration.ofSeconds(60))
                .tags(Set.of("推理", "通用"))
                .weight(80)
                .costPer1KTokens(0.003)
                .status(ModelMetadata.ModelStatus.ENABLED)
                .build();

        assertThat(meta.id()).isEqualTo("openai:gpt-4o");
        assertThat(meta.status()).isEqualTo(ModelMetadata.ModelStatus.ENABLED);
        assertThat(meta.tags()).containsExactlyInAnyOrder("推理", "通用");
        assertThat(meta.weight()).isEqualTo(80);
        assertThat(meta.costPer1KTokens()).isEqualByComparingTo(0.003);
    }

    @Test
    void modelMetadata_defaults_whenUnspecified() {
        ModelMetadata meta = new ModelMetadata.Builder("m").build();

        assertThat(meta.status()).isEqualTo(ModelMetadata.ModelStatus.ENABLED);
        assertThat(meta.tags()).isEmpty();
        assertThat(meta.weight()).isEqualTo(1);
    }

    @Test
    void routeRule_buildsForIntentAndRouteType() {
        RouteRule rule = new RouteRule("r1", Intent.REASONING, RouteType.REASONING, "openai:gpt-4o");

        assertThat(rule.id()).isEqualTo("r1");
        assertThat(rule.intent()).isEqualTo(Intent.REASONING);
        assertThat(rule.routeType()).isEqualTo(RouteType.REASONING);
        assertThat(rule.targetModelId()).isEqualTo("openai:gpt-4o");
    }

    @Test
    void routeRule_fallbackDefaultsEmptyAndAcceptsChain() {
        // 4 参构造：无备链（向后兼容，单模型退化）
        RouteRule single = new RouteRule("r1", Intent.REASONING, RouteType.REASONING, "m1");
        assertThat(single.fallbackModelIds()).isEmpty();

        // 5 参构造：主 + 备链
        RouteRule withFallback = new RouteRule("r1", Intent.REASONING, RouteType.REASONING,
                "primary-large", List.of("backup-large"));
        assertThat(withFallback.targetModelId()).isEqualTo("primary-large");
        assertThat(withFallback.fallbackModelIds()).containsExactly("backup-large");
    }

    @Test
    void flowControlPolicy_buildsWithAllLimitations() {
        FlowControlPolicy policy = new FlowControlPolicy("fc1", 100, 200000, Duration.ofSeconds(1));

        assertThat(policy.maxRequestsPerSecond()).isEqualTo(100);
        assertThat(policy.maxTokensPerDay()).isEqualTo(200000);
        assertThat(policy.window()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void failoverPolicy_buildsWithFallbacksAndStrategy() {
        FailoverPolicy policy = new FailoverPolicy.Builder("fo1")
                .maxRetries(3)
                .fallbackModelIds(List.of("fallback:small", "fallback:alt"))
                .timeout(Duration.ofSeconds(30))
                .strategy(FailoverStrategy.WEIGHTED)
                .build();

        assertThat(policy.maxRetries()).isEqualTo(3);
        assertThat(policy.fallbackModelIds()).hasSize(2);
        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.strategy()).isEqualTo(FailoverStrategy.WEIGHTED);
    }

    @Test
    void failoverPolicy_defaults_safely() {
        FailoverPolicy policy = new FailoverPolicy.Builder("x").build();

        assertThat(policy.maxRetries()).isEqualTo(0);
        assertThat(policy.fallbackModelIds()).isEmpty();
        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
