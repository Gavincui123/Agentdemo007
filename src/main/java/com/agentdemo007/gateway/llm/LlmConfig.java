package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.config.LlmProperties;
import com.agentdemo007.gateway.config.LlmPropertiesModelConfigSource;
import com.agentdemo007.gateway.config.ModelConfigSource;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.ModelCircuitBreaker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 主备容灾装配（{@code llm.enabled=true} 时激活）。
 *
 * <p>把 {@link LlmProperties} 装配为可注入 bean（{@code @EnableConfigurationProperties}），并按 providers 构造：
 * <ul>
 *   <li>每 provider 一个 {@link LangChain4jModelExecutor}（叶子，含端点/密钥/关思考参数；HTTP 请求序列化/响应
 *       解析/超时/重试全归 LC4j {@code OpenAiChatModel}，退役自研 {@code OpenAiModelExecutor}-HTTP +
 *       {@code RestTemplate}/{@code ObjectMapper}——本工程 LLM 出站栈不再依赖 Spring HTTP/Jackson3，
 *       仅 LC4j 内置 Jackson2 完成请求/响应序列化）；</li>
 *   <li>{@link RoutingModelExecutor} 持 {@code {id}-large/-small → (exec, raw 模型串)} 路由表
 *       ——合成 id（路由/熔断用）与 raw 串（发往 API）解耦；</li>
 *   <li>{@link ModelCircuitBreaker}（per-modelId 滑动窗口熔断，参数取自 {@code llm.circuit-breaker}）；</li>
 *   <li>{@link CircuitBreakingModelExecutor} 装饰路由执行器 + 熔断器——对外暴露为唯一 {@link ModelExecutor} bean
 *       （替代 {@code llm.enabled=false} 时 {@code GatewayConfig} 的 Noop 占位）。</li>
 * </ul>
 *
 * <p>真 {@link ModelConfigSource}（{@link LlmPropertiesModelConfigSource}）在此装配——
 * {@code ModelConfigBootstrap} 启动即 {@code refresh()} 把 4 模型 + 5 路由规则落地到注册表，
 * 主备链经 {@code ChatLlmService.invoke} 按意图取 {@code RouteRule.fallbackModelIds} 建 FailoverPolicy。
 *
 * <p>与 {@link com.agentdemo007.gateway.config.GatewayConfig} 互斥：以 {@code llm.enabled} 属性门控
 * （非 bean 存在性），装配顺序无关、可调试——{@code true} 走本类真执行器/源，
 * {@code false}/缺省走 GatewayConfig 的 Noop 占位（dev 降级，不调真实引擎）。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
@ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true")
public class LlmConfig {

    @Bean
    ModelConfigSource modelConfigSource(LlmProperties props) {
        return new LlmPropertiesModelConfigSource(props);
    }

    @Bean
    ModelCircuitBreaker modelCircuitBreaker(LlmProperties props) {
        LlmProperties.CircuitBreaker cb = props.getCircuitBreaker();
        return new ModelCircuitBreaker(cb.getFailureThreshold(), cb.getWindowMs(),
                cb.getCooldownMs(), System::currentTimeMillis);
    }

    @Bean
    ModelExecutor modelExecutor(LlmProperties props, ModelCircuitBreaker breaker, ExceptionTriage triage) {
        return new CircuitBreakingModelExecutor(
                buildRoutingExecutor(props), breaker, triage);
    }

    /**
     * 可单测的工厂：把 providers 翻成路由执行器——每 provider 一个 {@link LangChain4jModelExecutor}，
     * 注册 {@code {id}-large}/{@code {id}-small} 两条路由，value 为 (exec, raw 模型串)。
     * 合成 id 与 {@link LlmPropertiesModelConfigSource} 的 modelId 同构（路由表键=注册表 id=熔断 key）。
     *
     * <p>关思考透传：把每 provider 的 {@code disable-thinking-params}（SiliconFlow {@code enable_thinking:false}
     * / SenseNova {@code reasoning_effort:none}）注入执行器——<b>是否合并由每请求</b>
     * {@code request.disableThinking()} 决定（意图驱动：闲聊恒关、非闲聊由 {@code llm.thinking.enabled} 定），
     * 非构造期开关；合并发生在 {@link LangChain4jModelExecutor} 内 {@code customParameters} 注入处，
     * 引擎无关 seam（{@link ModelExecutor}）不变。
     *
     * <p>超时：{@code llm.timeout}（默认 60s，{@link LlmProperties#getTimeout()}）——超时预算须显著
     * 小于 SSE 异步超时（{@code app.sse.timeout-ms} 默认 120s），否则单次超时吃满 SSE 窗口、超时兜底
     * 话术来不及发（实测事故：旧硬编码 120s == SSE 120s，首次超时 + LC4j 内部重试 121s 才"成功"，
     * 而连接已被容器掐断）。超时后重试/转移/降级归网关层（ResilientExecutor/FailoverExecutor/熔断）。
     */
    static RoutingModelExecutor buildRoutingExecutor(LlmProperties props) {
        Map<String, RoutingModelExecutor.Route> routes = new LinkedHashMap<>();
        for (LlmProperties.Provider p : props.getProviders()) {
            LangChain4jModelExecutor exec = new LangChain4jModelExecutor(p.getBaseUrl(), p.getApiKey(),
                    p.getDisableThinkingParams(), props.getTimeout());
            routes.put(p.getId() + "-large", new RoutingModelExecutor.Route(exec, p.getLargeModel()));
            routes.put(p.getId() + "-small", new RoutingModelExecutor.Route(exec, p.getSmallModel()));
        }
        return new RoutingModelExecutor(routes);
    }
}
