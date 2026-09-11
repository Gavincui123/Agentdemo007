package com.agentdemo007.gateway.llm;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 SiliconFlow 冒烟（env-gated）：验 {@link OpenAiChatModel}（LC4j 原生 OpenAI 兼容 HTTP 客户端）
 * 替自研 {@code OpenAiModelExecutor}-HTTP 后，真打 SF 端点 + {@code enable_thinking=false} 关思考路径可跑通。
 *
 * <p><b>运行方式</b>（密钥经环境变量注入，不落明文——[[phase-llm-primary-backup-breaker]] 铁律）：
 * <pre>
 * export SF_KEY=sk-你的SiliconFlow密钥
 * export SF_MODEL=Qwen/Qwen3-14B   # 可选，默认 Qwen/Qwen3-14B；SF 文档支持 enable_thinking 的：Qwen3 全系列/DeepSeek V3.1/V3.2/GLM-5.1/Kimi-K2.x 等
 * ./mvnw -Dtest=OpenAiChatModelSiliconFlowSmokeTest test
 * </pre>
 * 无 SF_KEY 时自动跳过（{@link Assumptions#assumeTrue}），不报错。
 *
 * <p>关思考铁律（[[phase-llm-primary-backup-breaker]] [[dont-hardwrite-use-dep-methods]]）：
 * 冒烟即验"决策/闲聊调用关思考"路径——{@code customParameters(enable_thinking=false)} 经
 * {@link OpenAiChatModel} 序列化进请求体（[[OpenAiChatModelBodyCaptureTest]] 已恒 GREEN 实测坐实），
 * SF 端据此关推理链、直出答案。
 */
class OpenAiChatModelSiliconFlowSmokeTest {

    @Test
    void realSiliconFlow_enableThinkingFalse_returnsNonBlankAnswer() {
        String key = System.getenv("SF_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "SF_KEY 未设置——跳过真冒烟（设置后验真实 SF 全链路）");
        String model = System.getenv("SF_MODEL");
        if (model == null || model.isBlank()) {
            model = "Qwen/Qwen3-14B";
        }

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl("https://api.siliconflow.cn/v1")
                .apiKey(key)
                .modelName(model)
                .timeout(Duration.ofSeconds(90)) // SF 推理可能慢，宽放
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .customParameters(Map.of("enable_thinking", false)) // 关思考注入
                        .build())
                .build();

        String resp = chatModel.chat("你好，请用一句话自我介绍");

        System.out.println("SF smoke [" + model + "] enable_thinking=false → " + resp);

        assertThat(resp).isNotBlank();
    }
}
