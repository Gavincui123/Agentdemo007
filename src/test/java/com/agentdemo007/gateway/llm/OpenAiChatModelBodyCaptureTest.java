package com.agentdemo007.gateway.llm;

import com.agentdemo007.capability.business.OrderQueryService;
import com.agentdemo007.capability.tool.OrderQueryTool;
import com.agentdemo007.capability.tool.ToolSchemaProvider;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
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

    /**
     * 诊断（2026-09-12）：捕 tools 请求体，对照 SF 官方 API 手册排查 T1-T3 的 {@code 20015 "parameter invalid"}。
     * 真 SF 真打 Qwen3-14B+enable_thinking+tools→20015；纯 chat+enable_thinking→GREEN
     * （{@link OpenAiChatModelSiliconFlowSmokeTest}）。用户纠正：Qwen3-14B 支持 FC（官方 API 手册 tools 为
     * 通用 OpenAI 兼容参）。故用假 transport 捕 LC4j 全序列化请求体，肉眼/断言排查是否有 SF 拒收字段
     * （如 {@code strict} / 多余 schema 字段），并对比"带 enable_thinking"与"仅 tools"两体差异。无网络无 key 恒 GREEN。
     */
    @Test
    void toolsRequestBody_capture_diagnostic() {
        ToolSchemaProvider schemas = new ToolSchemaProvider(List.of(
                new OrderQueryTool(new OrderQueryService())));
        List<ToolSpecification> specs = schemas.allSchemas();
        assertThat(specs).isNotEmpty();

        // 体 A：tools + enable_thinking（复刻 T1-T3 失败请求）
        CapturingHttpClient clientA = new CapturingHttpClient();
        OpenAiChatModel modelA = OpenAiChatModel.builder()
                .baseUrl("https://api.siliconflow.cn/v1")
                .apiKey("dummy")
                .modelName("Qwen/Qwen3-14B")
                .httpClientBuilder(new CapturingHttpClientBuilder(clientA))
                .build();
        OpenAiChatRequestParameters paramsA = OpenAiChatRequestParameters.builder()
                .customParameters(Map.of("enable_thinking", false))
                .toolSpecifications(specs)
                .build();
        modelA.doChat(ChatRequest.builder()
                .messages(new UserMessage("查订单 ORD-001"))
                .parameters(paramsA)
                .build());
        System.out.println("=== DIAG BODY A (Qwen3-14B + enable_thinking=false + tools) ===");
        System.out.println(clientA.capturedBody);

        // 体 B：仅 tools（不带 enable_thinking——隔离 enable_thinking 嫌疑）
        CapturingHttpClient clientB = new CapturingHttpClient();
        OpenAiChatModel modelB = OpenAiChatModel.builder()
                .baseUrl("https://api.siliconflow.cn/v1")
                .apiKey("dummy")
                .modelName("Qwen/Qwen3-14B")
                .httpClientBuilder(new CapturingHttpClientBuilder(clientB))
                .build();
        OpenAiChatRequestParameters paramsB = OpenAiChatRequestParameters.builder()
                .toolSpecifications(specs)
                .build();
        modelB.doChat(ChatRequest.builder()
                .messages(new UserMessage("查订单 ORD-001"))
                .parameters(paramsB)
                .build());
        System.out.println("=== DIAG BODY B (Qwen3-14B + tools, NO enable_thinking) ===");
        System.out.println(clientB.capturedBody);

        // 体 C：tools + enable_thinking + modelName（修复候选——补 modelName 防 model 字段缺失）
        CapturingHttpClient clientC = new CapturingHttpClient();
        OpenAiChatModel modelC = OpenAiChatModel.builder()
                .baseUrl("https://api.siliconflow.cn/v1")
                .apiKey("dummy")
                .modelName("Qwen/Qwen3-14B")
                .httpClientBuilder(new CapturingHttpClientBuilder(clientC))
                .build();
        OpenAiChatRequestParameters paramsC = OpenAiChatRequestParameters.builder()
                .modelName("Qwen/Qwen3-14B") // 修复：per-request params 覆盖 default params 致 model 丢失→须显式带
                .customParameters(Map.of("enable_thinking", false))
                .toolSpecifications(specs)
                .build();
        modelC.doChat(ChatRequest.builder()
                .messages(new UserMessage("查订单 ORD-001"))
                .parameters(paramsC)
                .build());
        System.out.println("=== DIAG BODY C (Qwen3-14B + modelName + enable_thinking + tools) ===");
        System.out.println(clientC.capturedBody);

        assertThat(clientA.capturedBody).contains("\"tools\"");
        assertThat(clientB.capturedBody).contains("\"tools\"");
        // 坐实根因：A/B（未带 modelName）body 缺 "model" 字段；C（带 modelName）body 有 "model" + 模型名
        assertThat(clientA.capturedBody).doesNotContain("\"model\"");
        assertThat(clientC.capturedBody).contains("\"model\"");
        assertThat(clientC.capturedBody).contains("Qwen/Qwen3-14B");
    }
}
