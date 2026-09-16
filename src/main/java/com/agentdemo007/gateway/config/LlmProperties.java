package com.agentdemo007.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code llm.*} 配置绑定（主备容灾·T7）。
 *
 * <p>Nacos/dev 的 {@code llm} 段经 Spring relaxed binding 绑定到此 POJO，再由
 * {@link LlmPropertiesModelConfigSource} 翻成 {@link ModelConfigSnapshot}。结构对齐实际 dataId：
 * <pre>
 * llm:
 *   enabled: true
 *   thinking:                       # 思考模式开关（意图驱动：闲聊恒关；非闲聊由 enabled 定，默认 true=开思考）
 *     enabled: true
 *   providers:
 *     - id: siliconflow
 *       base-url: https://api.siliconflow.cn/v1
 *       api-key: ${SF_KEY}
 *       large-model: Qwen/Qwen3.5-35B-A3B
 *       small-model: Qwen/Qwen3.5-27B
 *       disable-thinking-params:    # SiliconFlow Qwen3.5 关思考：enable_thinking=false（请求 disableThinking=true 时合并）
 *         enable_thinking: false
 *     - id: aliyun                       # 备（阿里云 DashScope，OpenAI 兼容）
 *       base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
 *       api-key: ${ALIYUN_KEY}
 *       large-model: qwen3.8-max
 *       small-model: deepseek-v4-pro-0813
 *       disable-thinking-params:    # Aliyun 关思考：enable_thinking=false（请求 disableThinking=true 时合并）
 *         enable_thinking: false
 *   circuit-breaker:
 *     window-ms: 60000
 *     failure-threshold: 5
 *     cooldown-ms: 30000
 * </pre>
 * {@code providers} 首位=主（打 RouteType 标签、路由主选），其余=备（不打标签、仅经 fallback 链可达）。
 * 密钥应经环境变量注入（{@code ${...}} 占位），不落明文——此 POJO 只承载绑定，不校验来源。
 */
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private boolean enabled = false;
    private List<Provider> providers = new ArrayList<>();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private Thinking thinking = new Thinking();
    private Duration timeout = Duration.ofSeconds(20);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }

    /**
     * 单次 LLM HTTP 调用超时（{@code llm.timeout}，默认 20s）。
     *
     * <p>超时预算治理：须显著小于 SSE 异步超时 {@code app.sse.timeout-ms}（默认 120s）——
     * 单次超时吃满 SSE 窗口会导致超时兜底话术来不及发（容器先掐断连接）。超时后由网关层
     * （ResilientExecutor 同模型退避 + FailoverExecutor 主备转移 + 熔断）统一重试/降级，
     * LC4j 内部重试已关闭（{@code maxRetries=0}），不再叠加放大延迟。20s 依据：关思考后
     * 各调用实测 0.6~2.8s，20s=7~30 倍余量；实测 provider 间歇挂起时阈值越低单次损失越小。
     */
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) {
        this.timeout = (timeout == null || timeout.isNegative() || timeout.isZero())
                ? Duration.ofSeconds(20) : timeout;
    }

    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker == null ? new CircuitBreaker() : circuitBreaker;
    }

    /**
     * 思考模式开关（意图驱动·非闲聊意图由本开关定）：
     * {@code llm.thinking.enabled=true}（默认）→ 非闲聊开思考；{@code false} → 非闲聊也关思考。
     * 闲聊(CHIT_CHAT)无论本开关恒关思考（在 {@code ChatLlmService.invoke} 按意图算定 disableThinking）。
     */
    public Thinking getThinking() { return thinking; }
    public void setThinking(Thinking thinking) { this.thinking = thinking == null ? new Thinking() : thinking; }

    /** 思考模式开关 POJO（{@code llm.thinking.enabled}，默认 true=开思考）。 */
    public static class Thinking {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** 单服务商：标识 + 端点 + 密钥 + 大/小模型 API 名 + 关思考参数。 */
    public static class Provider {
        private String id;
        private String baseUrl;
        private String apiKey;
        private String largeModel;
        private String smallModel;
        private Map<String, Object> disableThinkingParams = Map.of();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getLargeModel() { return largeModel; }
        public void setLargeModel(String largeModel) { this.largeModel = largeModel; }
        public String getSmallModel() { return smallModel; }
        public void setSmallModel(String smallModel) { this.smallModel = smallModel; }

        /**
         * 该 provider 关思考的请求体参数（每请求 {@code disableThinking=true} 时合并进 body）。
         * 因不同服务商关思考参数<b>可能不同</b>（per-provider 配置，官方文档核实）：
         * SiliconFlow / Aliyun 用 {@code enable_thinking: false}（boolean），
         * 部分服务商用 {@code reasoning_effort: none}（string）等其他参数名。
         * flash-lite 无官方思考开关 → 留空（{@code {}}）。
         */
        public Map<String, Object> getDisableThinkingParams() { return disableThinkingParams; }
        public void setDisableThinkingParams(Map<String, Object> disableThinkingParams) {
            this.disableThinkingParams = disableThinkingParams == null ? Map.of() : disableThinkingParams;
        }
    }

    /** 模型级熔断参数（滑动窗口失败计数）。 */
    public static class CircuitBreaker {
        private long windowMs = 60000;
        private int failureThreshold = 5;
        private long cooldownMs = 30000;

        public long getWindowMs() { return windowMs; }
        public void setWindowMs(long windowMs) { this.windowMs = windowMs; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public long getCooldownMs() { return cooldownMs; }
        public void setCooldownMs(long cooldownMs) { this.cooldownMs = cooldownMs; }
    }
}
