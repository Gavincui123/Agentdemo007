package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.config.LlmProperties;
import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ① wiring 真 SiliconFlow 冒烟（env-gated）：验 {@link LangChain4jModelExecutor} 经
 * {@link LlmConfig#buildRoutingExecutor} 装配后，真打 SF 端点 + {@code enable_thinking=false} 关思考路径可跑通。
 *
 * <p>与 {@code OpenAiChatModelSiliconFlowSmokeTest}（裸 OpenAiChatModel.chat，验 ②）不同：本冒烟走
 * <b>① 的真 wiring 链</b>——{@code LlmConfig.buildRoutingExecutor}（按 provider 装 LangChain4jModelExecutor）
 * → {@link RoutingModelExecutor}（合成 id→raw 模型串翻译）→ {@link LangChain4jModelExecutor}
 * （LlmRequest→OpenAiChatModel.chat→LlmResponse，含 disableThinking→customParameters、maxTokens、tokens 落账）。
 *
 * <p><b>运行方式</b>（密钥经环境变量注入，不落明文——[[phase-llm-primary-backup-breaker]] 铁律）：
 * <pre>
 * export SF_KEY=sk-你的SiliconFlow密钥
 * export JAVA_HOME=/Users/cuizhifeng/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home
 * ./mvnw -Dtest=LangChain4jModelExecutorSiliconFlowSmokeTest test
 * </pre>
 * 无 SF_KEY 时自动跳过（{@link EnabledIfEnvironmentVariable}），不影响日常 GREEN 基线。
 *
 * <p>用 {@code Qwen/Qwen3-14B}（上轮 [[phase-llm-primary-backup-breaker]] 已验 enable_thinking 合法）+
 * {@code disableThinking=true}（关思考铁律落地路径：闲聊/决策恒关）。合成 id {@code siliconflow-large} 经
 * {@link RoutingModelExecutor} 翻译成 raw 模型串回传进 {@code LlmResponse.modelId()}（路由层契约）。
 * 关联 [[langchain4j-boot4-compat-findings]] [[dont-hardwrite-use-dep-methods]]。
 */
@EnabledIfEnvironmentVariable(named = "SF_KEY", matches = "sk-.+", disabledReason = "缺 SF_KEY：跳过 ① wiring 真冒烟")
class LangChain4jModelExecutorSiliconFlowSmokeTest {

    private static final String SF_BASE = "https://api.siliconflow.cn/v1";
    private static final String SF_MODEL = "Qwen/Qwen3-14B"; // 上轮已验 enable_thinking 合法
    private static final String PROMPT = "用一句话自我介绍";

    /** 单 SF provider，配关思考参数（disableThinking=true 时注入 enable_thinking=false）。 */
    private static LlmProperties props(String sfKey) {
        LlmProperties.Provider prov = new LlmProperties.Provider();
        prov.setId("siliconflow");
        prov.setBaseUrl(SF_BASE);
        prov.setApiKey(sfKey);
        prov.setLargeModel(SF_MODEL);
        prov.setSmallModel(SF_MODEL);
        prov.setDisableThinkingParams(Map.of("enable_thinking", false));
        LlmProperties p = new LlmProperties();
        p.setProviders(List.of(prov));
        return p;
    }

    @Test
    void buildRoutingExecutor_throughLangChain4jModelExecutor_realSfReturnsContentAndTokens() {
        LlmProperties props = props(System.getenv("SF_KEY"));
        // ① wiring：LlmConfig.buildRoutingExecutor 按 provider 装 LangChain4jModelExecutor（退役 OpenAiModelExecutor-HTTP）
        ModelExecutor exec = LlmConfig.buildRoutingExecutor(props);

        // disableThinking=true → 关思考路径（闲聊/决策铁律；LangChain4jModelExecutor 注入 enable_thinking=false）
        LlmResponse resp = exec.execute(new LlmRequest("siliconflow-large", PROMPT, 1024, true));

        // RoutingModelExecutor 回映射：合成 id siliconflow-large → leaf 按 raw 执行 → 响应 modelId 回映射为合成 id
        // （路由层"不渗入 raw 串"契约——调用方见稳定合成 id，provider raw 串不外泄）
        assertThat(resp.modelId()).isEqualTo("siliconflow-large");
        assertThat(resp.content()).isNotBlank();
        assertThat(resp.tokens()).isGreaterThan(0); // 真实 tokens 落账（UnifiedModelGateway 预算记账需要）
        System.out.printf("[①冒烟] siliconflow-large disableThinking=true → tokens=%d content=%s%n",
                resp.tokens(), resp.content());
    }
}
