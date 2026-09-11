package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.config.LlmProperties;
import com.agentdemo007.gateway.config.LlmPropertiesModelConfigSource;
import com.agentdemo007.gateway.config.ModelConfigSource;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.ModelCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmConfig} 装配测试（主备容灾·T9）。
 *
 * <p>用 {@link ApplicationContextRunner} 隔离装配（不拉 Nacos/JPA/Rabbit，确定性），断言属性门控：
 * <ul>
 *   <li>{@code llm.enabled=true}：装出 {@link CircuitBreakingModelExecutor}（真 ModelExecutor）、
 *       {@link LlmPropertiesModelConfigSource}（真源）、{@link ModelCircuitBreaker}、{@link LlmProperties}，
 *       且熔断参数经 relaxed binding 自 {@code llm.circuit-breaker} 落入；</li>
 *   <li>{@code llm.enabled=false}：{@link LlmConfig} 整体不激活——不贡献 ModelExecutor/ModelConfigSource bean
 *      （由 {@code GatewayConfig} 的 Noop 占位接管，本测试不加载 GatewayConfig 故断言 LlmConfig 不贡献）。</li>
 * </ul>
 */
class LlmConfigWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class)
            .withBean(ExceptionTriage.class);

    @Test
    void enabled_wiresRealExecutorSourceAndBreaker() {
        runner.withPropertyValues(
                "llm.enabled=true",
                "llm.providers[0].id=siliconflow",
                "llm.providers[0].base-url=https://api.siliconflow.cn/v1",
                "llm.providers[0].api-key=sk-sf",
                "llm.providers[0].large-model=Qwen/Qwen3.5-35B-A3B",
                "llm.providers[0].small-model=Qwen/Qwen3.5-27B",
                "llm.providers[1].id=deepseek",
                "llm.providers[1].base-url=https://token.sensenova.cn/v1",
                "llm.providers[1].api-key=sk-ds",
                "llm.providers[1].large-model=deepseek-v4-flash",
                "llm.providers[1].small-model=sensenova-6.8-flash-lite",
                "llm.circuit-breaker.window-ms=60000",
                "llm.circuit-breaker.failure-threshold=5",
                "llm.circuit-breaker.cooldown-ms=30000"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModelExecutor.class);
            assertThat(context.getBean(ModelExecutor.class))
                    .isInstanceOf(CircuitBreakingModelExecutor.class);
            assertThat(context).hasSingleBean(ModelConfigSource.class);
            assertThat(context.getBean(ModelConfigSource.class))
                    .isInstanceOf(LlmPropertiesModelConfigSource.class);
            assertThat(context).hasSingleBean(ModelCircuitBreaker.class);
            assertThat(context).hasSingleBean(LlmProperties.class);
            // 熔断参数绑定自 llm.circuit-breaker
            LlmProperties.CircuitBreaker cb = context.getBean(LlmProperties.class).getCircuitBreaker();
            assertThat(cb.getFailureThreshold()).isEqualTo(5);
            assertThat(cb.getWindowMs()).isEqualTo(60000L);
            assertThat(cb.getCooldownMs()).isEqualTo(30000L);
        });
    }

    @Test
    void disabled_doesNotContributeLlmBeans() {
        runner.withPropertyValues("llm.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ModelExecutor.class);
            assertThat(context).doesNotHaveBean(ModelConfigSource.class);
        });
    }
}
