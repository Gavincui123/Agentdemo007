package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.LlmProperties;
import com.agentdemo007.gateway.core.FailoverExecutor;
import com.agentdemo007.gateway.core.GatewayRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.resilience.ExceptionTriage;
import com.agentdemo007.resilience.ModelCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 LLM 主备冒烟（主备容灾·T10）。
 *
 * <p>经 {@code @EnabledIfEnvironmentVariable} 门控：无 {@code SF_KEY}/{@code ALIYUN_KEY} 时整类跳过
 * （不影响日常 GREEN 基线）；注入两把真实 key 时按需运行，端到端验证主备容灾全链路
 * （与 {@code LlmConfig}+{@code GatewayConfig} 装配等价的真执行器栈）：
 * <ol>
 *   <li><b>主成功</b>：真 SiliconFlow key → {@code siliconflow-large} 关思考返回真实回复；</li>
 *   <li><b>断主→切备</b>：主用错误 key（必鉴权失败）→ 自动故障转移至 {@code aliyun-large}
 *       （阿里云 DashScope OpenAI 兼容模式）返回真实回复。</li>
 * </ol>
 *
 * <p><b>备选 provider：阿里云 DashScope</b>（{@code https://dashscope.aliyuncs.com/compatible-mode/v1}，OpenAI 兼容），
 * 经 ① 的 {@link LangChain4jModelExecutor} 引擎无关 seam 接入——换 provider 是纯配置替换，零新接入口
 * （执行器只认 baseUrl/apiKey/disableThinkingParams，DashScope 是 OpenAI 兼容端点即接）。
 *
 * <p><b>关思考路径</b>（镜像 prod 闲聊/决策铁律 + 避推理模型思考撞 1024 预算空 content）：
 * 每请求 {@code disableThinking=true}，每 provider 的 {@code disable-thinking-params}
 * （{@code enable_thinking=false}）合并进请求体。主（SF）与备（Aliyun）都关思考，稳出 content。
 *
 * <p>密钥只从环境变量读、不落代码（遵守「密钥经环境变量注入、不落明文」）。栈构造镜像生产：
 * {@link LlmConfig#buildRoutingExecutor} → {@link CircuitBreakingModelExecutor}（熔断装饰）→ {@link FailoverExecutor}
 * （按 {@code FailoverPolicy} 备链切备）。关联 [[phase-llm-primary-backup-breaker]] [[langchain4j-boot4-compat-findings]]。
 */
@EnabledIfEnvironmentVariable(named = "SF_KEY", matches = "sk-.+", disabledReason = "缺 SF_KEY：跳过真实 LLM 冒烟")
@EnabledIfEnvironmentVariable(named = "ALIYUN_KEY", matches = "sk-.+", disabledReason = "缺 ALIYUN_KEY：跳过真实 LLM 冒烟")
class RealLlmFailoverSmokeTest {

    private static final String SF_BASE = "https://api.siliconflow.cn/v1";
    private static final String SF_LARGE = "Qwen/Qwen3-14B"; // 关思考路径用 14B（已验合法、快、稳）
    private static final String ALIYUN_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String ALIYUN_LARGE = "qwen3.8-max";
    private static final String ALIYUN_SMALL = "deepseek-v4-pro-0813";

    private static final String PROMPT = "用一句话说你好";
    private static final Map<String, Object> THINKING_OFF = Map.of("enable_thinking", false);

    /** 镜像生产真执行器栈：路由执行器 + 模型级熔断装饰（参数同 llm.circuit-breaker 默认 5/60s/30s）。 */
    private static CircuitBreakingModelExecutor realExecutor(LlmProperties props) {
        ModelCircuitBreaker breaker = new ModelCircuitBreaker(5, 60000L, 30000L, System::currentTimeMillis);
        return new CircuitBreakingModelExecutor(
                LlmConfig.buildRoutingExecutor(props), breaker, new ExceptionTriage());
    }

    private static LlmProperties props(String sfKey, String aliyunKey) {
        LlmProperties p = new LlmProperties();
        p.setProviders(List.of(
                provider("siliconflow", SF_BASE, sfKey, SF_LARGE, SF_LARGE),
                provider("aliyun", ALIYUN_BASE, aliyunKey, ALIYUN_LARGE, ALIYUN_SMALL)));
        return p;
    }

    private static LlmProperties.Provider provider(String id, String url, String key, String large, String small) {
        LlmProperties.Provider p = new LlmProperties.Provider();
        p.setId(id);
        p.setBaseUrl(url);
        p.setApiKey(key);
        p.setLargeModel(large);
        p.setSmallModel(small);
        p.setDisableThinkingParams(THINKING_OFF); // 关思考参数（disableThinking=true 时合并 enable_thinking=false）
        return p;
    }

    @Test
    void primarySiliconflow_returnsRealReply() {
        LlmProperties props = props(System.getenv("SF_KEY"), System.getenv("ALIYUN_KEY"));
        CircuitBreakingModelExecutor exec = realExecutor(props);
        FailoverExecutor failover = new FailoverExecutor();
        FailoverPolicy policy = FailoverPolicy.builder("smoke-primary")
                .fallbackModelIds(List.of("aliyun-large"))
                .maxRetries(1)
                .build();

        // disableThinking=true 关思考（镜像 prod 闲聊/决策；避推理模型思考撞 1024 预算空 content→LlmUnavailable→切备）
        LlmResponse resp = failover.execute(
                new GatewayRequest("siliconflow-large", PROMPT, 1024, policy, null, true), exec);

        // 主成功须由主本身产出（非静默切备）：断言 modelId=siliconflow-large + 非空 content。
        assertThat(resp.modelId()).isEqualTo("siliconflow-large");
        assertThat(resp.content()).isNotBlank();
        System.out.printf("[冒烟] 主成功 siliconflow-large → tokens=%d content=%s%n",
                resp.tokens(), resp.content());
    }

    @Test
    void primaryBrokenKey_failoversToBackupAliyun() {
        // 主用错误 key（必鉴权失败），备用真 key——验证自动切备
        LlmProperties props = props("sk-invalid-will-fail", System.getenv("ALIYUN_KEY"));
        CircuitBreakingModelExecutor exec = realExecutor(props);
        FailoverExecutor failover = new FailoverExecutor();
        FailoverPolicy policy = FailoverPolicy.builder("smoke-failover")
                .fallbackModelIds(List.of("aliyun-large"))
                .maxRetries(1)
                .build();

        LlmResponse resp = failover.execute(
                new GatewayRequest("siliconflow-large", PROMPT, 1024, policy, null, true), exec);

        // 主鉴权失败 → 切备 aliyun-large（DashScope qwen3.8-max 关思考）：断言 modelId=aliyun-large + 非空 content。
        assertThat(resp.modelId()).isEqualTo("aliyun-large");
        assertThat(resp.content()).isNotBlank();
        System.out.printf("[冒烟] 断主→切备 aliyun-large → tokens=%d content=%s%n",
                resp.tokens(), resp.content());
    }
}
