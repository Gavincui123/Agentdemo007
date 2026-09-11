package com.agentdemo007.gateway.llm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证 {@link OpenAiChatModel}（LC4j 原生 OpenAI 兼容 HTTP 客户端）把 {@code customParameters} 序列化进
 * 请求体顶层字段——即 SiliconFlow {@code enable_thinking} 经此注入可达 body（用户查官方文档确认，此处实测坐实）。
 *
 * <p>**无网络、无真实 key、恒 GREEN**：插假 {@link HttpClient}（LC4j transport seam，测试桩合规——
 * [[dont-hardwrite-use-dep-methods]] 豁免 test double）捕获 {@link HttpRequest#body()}（真 OpenAiChatModel
 * 全序列化路径产出的 JSON），返回罐装 OpenAI 响应。跑的是依赖原生序列化+解析，非手撸。
 *
 * <p>退役坐实：{@link OpenAiChatModel} 替自研 {@code OpenAiModelExecutor}-HTTP（请求序列化/响应解析/重试/超时
 * 全归 LC4j）；{@code enable_thinking} 经 {@code customParameters} 注入 body（项目"意图驱动关思考"铁律
 * 的落地路径——闲聊/决策调用 enable_thinking=false）。后续 per-request 动态切走 {@code ChatModelListener.onRequest}。
 * 关联 [[langchain4j-boot4-compat-findings]] [[dont-hardwrite-use-dep-methods]]。
 */
class OpenAiChatModelBodyCaptureTest {

    /** 假 transport：捕获请求体（证 enable_thinking 进了 body），返回罐装 OpenAI chat-completion 响应。 */
    static final class CapturingHttpClient implements HttpClient {
        String capturedBody;
        String capturedUrl;

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            this.capturedBody = request.body();
            this.capturedUrl = request.url();
            // 最小合法 OpenAI chat-completion 响应（OpenAiChatModel 解析 choices[0].message.content）
            String json = "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"Qwen/Qwen3-14B\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            return SuccessfulHttpResponse.builder().statusCode(200).body(json).build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            throw new UnsupportedOperationException("non-streaming test");
        }
    }

    /** 假 HttpClientBuilder：仅 build() 返回假 transport，超时返回安全默认。 */
    static final class CapturingHttpClientBuilder implements HttpClientBuilder {
        private final CapturingHttpClient client;

        CapturingHttpClientBuilder(CapturingHttpClient client) {
            this.client = client;
        }

        @Override
        public Duration connectTimeout() {
            return Duration.ofSeconds(60);
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration connectTimeout) {
            return this;
        }

        @Override
        public Duration readTimeout() {
            return Duration.ofSeconds(60);
        }

        @Override
        public HttpClientBuilder readTimeout(Duration readTimeout) {
            return this;
        }

        @Override
        public HttpClient build() {
            return client;
        }
    }

    @Test
    void openAiChatModel_serializesCustomParametersIntoBody_enableThinkingReached() {
        CapturingHttpClient client = new CapturingHttpClient();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.siliconflow.cn/v1")
                .apiKey("dummy-key") // 假 transport 不校验，仅占位
                .modelName("Qwen/Qwen3-14B")
                .httpClientBuilder(new CapturingHttpClientBuilder(client)) // 假 transport——无网络
                .defaultRequestParameters(OpenAiChatRequestParameters.builder()
                        .customParameters(Map.of("enable_thinking", false)) // 关思考注入
                        .build())
                .build();

        String response = model.chat("hi");

        // 全 round-trip：OpenAiChatModel 序列化请求→假 transport→解析罐装响应→取 content
        assertThat(response).contains("hello");
        // customParameters→请求体顶层字段（enable_thinking 进了 body——实测坐实官方文档结论）
        assertThat(client.capturedBody).contains("enable_thinking");
        assertThat(client.capturedBody).contains("false");
        // modelName→请求体（证模型名透传）
        assertThat(client.capturedBody).contains("Qwen/Qwen3-14B");
    }
}
