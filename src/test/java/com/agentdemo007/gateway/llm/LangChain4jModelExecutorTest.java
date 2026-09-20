package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 证 {@link LangChain4jModelExecutor}（{@link com.agentdemo007.gateway.core.ModelExecutor} 的 LC4j 实现）
 * 把 {@link LlmRequest} 翻译成 {@code OpenAiChatModel} 调用并产出 {@link LlmResponse}——退役自研
 * {@code OpenAiModelExecutor}-HTTP（请求序列化/响应解析/超时全归 LC4j，本类只做 LlmRequest↔ChatModel 翻译）。
 *
 * <p>**无网络、无真实 key、恒 GREEN**：插假 {@link HttpClient}（LC4j transport seam，测试桩合规——
 * [[dont-hardwrite-use-dep-methods]] 豁免 test double）捕获 {@link HttpRequest#body()}（真 OpenAiChatModel
 * 全序列化路径产出的 JSON），返回罐装 OpenAI 响应。跑的是依赖原生序列化+解析，非手撸。
 *
 * <p>翻译契约（镜像旧 {@code OpenAiModelExecutor} 语义，零行为漂移）：
 * <ul>
 *   <li>{@code request.modelId()} → {@code modelName}（请求体 model 字段）</li>
 *   <li>{@code request.prompt()} → {@code UserMessage}（请求体 messages[user].content）</li>
 *   <li>{@code request.maxTokens()} → {@code maxTokens}（请求体 max_tokens，>0 才设）</li>
 *   <li>{@code request.disableThinking()=true} 且 provider 配了关思考参数 → {@code customParameters} 注入
 *       （SF {@code enable_thinking=false}）；{@code disableThinking=false} 不注入（避免非推理模型 400）</li>
 *   <li>空 content → 抛 {@link LlmUnavailableException}（推理模型预算耗尽 content 空；不外泄思考过程）</li>
 *   <li>空 api-key → 抛 {@link LlmUnavailableException}（provider 未配 key 即降级，与 Noop 等价端态）</li>
 *   <li>tokenUsage.totalTokenCount() → {@link LlmResponse#tokens()}（{@code UnifiedModelGateway} 预算记账需要）</li>
 * </ul>
 *
 * <p>关联 [[langchain4j-boot4-compat-findings]] [[dont-hardwrite-use-dep-methods]] [[phase4-gateway-design]]
 * [[phase-llm-primary-backup-breaker]]（关思考铁律落地路径：闲聊/决策 disableThinking=true→enable_thinking=false）。
 */
class LangChain4jModelExecutorTest {

    /** 假 transport：捕获请求体（证翻译契约），返回构造期给定的罐装 OpenAI chat-completion 响应。 */
    static final class CapturingHttpClient implements HttpClient {
        String capturedBody = "";
        String capturedUrl;
        private final String cannedJson;

        CapturingHttpClient(String cannedJson) {
            this.cannedJson = cannedJson;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            this.capturedBody = request.body();
            this.capturedUrl = request.url();
            return SuccessfulHttpResponse.builder().statusCode(200).body(cannedJson).build();
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

    private static final String HELLO_JSON =
            "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"Qwen/Qwen3-14B\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}";

    private static final String EMPTY_CONTENT_JSON =
            "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"Qwen/Qwen3-14B\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                    + "\"finish_reason\":\"length\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    @Test
    void execute_translatesLlmRequestToOpenAiChatModel_andReturnsContentPlusTokens() {
        CapturingHttpClient client = new CapturingHttpClient(HELLO_JSON);
        LangChain4jModelExecutor exec = new LangChain4jModelExecutor(
                "https://api.siliconflow.cn/v1", "dummy-key", // 假 transport 不校验 key
                Map.of("enable_thinking", false), // SF 关思考参数
                Duration.ofSeconds(60), null,
                new CapturingHttpClientBuilder(client)); // 假 transport——无网络

        LlmResponse resp = exec.execute(new LlmRequest("Qwen/Qwen3-14B", "你好", 512, true)); // disableThinking=true

        // 全 round-trip：执行器把 LlmRequest 翻译→OpenAiChatModel 序列化→假 transport→解析罐装响应→content
        assertThat(resp.modelId()).isEqualTo("Qwen/Qwen3-14B");
        assertThat(resp.content()).isEqualTo("hello");
        // tokenUsage.totalTokenCount() 落账（UnifiedModelGateway 预算记账需要真实 tokens，非 0）
        assertThat(resp.tokens()).isEqualTo(7);
        // 关思考注入：disableThinking=true → enable_thinking=false 进请求体顶层字段（关思考铁律落地路径）
        assertThat(client.capturedBody).contains("enable_thinking");
        assertThat(client.capturedBody).contains("false");
        // maxTokens 透传到请求体（值 512 到达 body）
        assertThat(client.capturedBody).contains("512");
        // modelId 透传到请求体（model 字段）
        assertThat(client.capturedBody).contains("Qwen/Qwen3-14B");
        // prompt 透传到请求体（messages[user].content）
        assertThat(client.capturedBody).contains("你好");
    }

    @Test
    void execute_disableThinkingFalse_doesNotInjectThinkingParams_avoids400OnNonReasoningModels() {
        CapturingHttpClient client = new CapturingHttpClient(HELLO_JSON);
        LangChain4jModelExecutor exec = new LangChain4jModelExecutor(
                "https://api.siliconflow.cn/v1", "dummy-key",
                Map.of("enable_thinking", false),
                Duration.ofSeconds(60), null,
                new CapturingHttpClientBuilder(client));

        exec.execute(new LlmRequest("Qwen/Qwen3-14B", "你好", 512, false)); // disableThinking=false

        // 请求确实发出（非空——证 stub 没空跑）
        assertThat(client.capturedBody).isNotBlank();
        // 不注入关思考参数——避免对不支持该参数的 provider 报 400（SF 非推理模型 400 code=20015）
        assertThat(client.capturedBody).doesNotContain("enable_thinking");
    }

    @Test
    void execute_temperatureConfigured_serializedIntoBody() {
        CapturingHttpClient client = new CapturingHttpClient(HELLO_JSON);
        LangChain4jModelExecutor exec = new LangChain4jModelExecutor(
                "https://api.siliconflow.cn/v1", "dummy-key",
                Map.of("enable_thinking", false),
                Duration.ofSeconds(60), 0.2D,
                new CapturingHttpClientBuilder(client));

        exec.execute(new LlmRequest("Qwen/Qwen3-14B", "你好", 512, false));

        // 采样温度透传（2026-09-17 定案：provider 配置 temperature，默认 0.2 准确优先）
        assertThat(client.capturedBody).contains("temperature");
        assertThat(client.capturedBody).contains("0.2");
    }

    @Test
    void execute_temperatureNull_notSerialized_providerDefaultApplies() {
        CapturingHttpClient client = new CapturingHttpClient(HELLO_JSON);
        LangChain4jModelExecutor exec = new LangChain4jModelExecutor(
                "https://api.siliconflow.cn/v1", "dummy-key",
                Map.of("enable_thinking", false),
                Duration.ofSeconds(60), null,
                new CapturingHttpClientBuilder(client));

        exec.execute(new LlmRequest("Qwen/Qwen3-14B", "你好", 512, false));

        // 未配置温度 → 请求体不带 temperature 字段（走模型 provider 默认）
        assertThat(client.capturedBody).doesNotContain("temperature");
    }

    @Test
    void execute_blankApiKey_throwsLlmUnavailable_providerUnconfigured() {
        LangChain4jModelExecutor exec = new LangChain4jModelExecutor(
                "https://api.siliconflow.cn/v1", "", // 空 key——provider 未配
                Map.of("enable_thinking", false),
                Duration.ofSeconds(60), null); // 无需 transport——key 校验前置，不发请求

        // 空 key → LlmUnavailableException（与 Noop 占位等价端态：未配 key 即降级话术）
        assertThatThrownBy(() -> exec.execute(new LlmRequest("m", "p", 64, true)))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void execute_blankContent_throwsLlmUnavailable_reasoningBudgetExhausted() {
        CapturingHttpClient client = new CapturingHttpClient(EMPTY_CONTENT_JSON); // content="" finish_reason=length
        LangChain4jModelExecutor exec = new LangChain4jModelExecutor(
                "https://api.siliconflow.cn/v1", "dummy-key",
                Map.of("enable_thinking", false),
                Duration.ofSeconds(60), null,
                new CapturingHttpClientBuilder(client));

        // 空 content = 模型失败（推理模型预算耗尽 content 空）——不外泄思考过程，抛 LlmUnavailableException
        assertThatThrownBy(() -> exec.execute(new LlmRequest("Qwen/Qwen3-14B", "你好", 512, true)))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void execute_withMessagesAndTools_sendsToolsInBody_andReturnsToolCalls() throws Exception {
        // 罐装 OpenAI 响应：模型发起 tool_call（content=null, tool_calls=[triangleArea], finish_reason=tool_calls）
        String toolCallJson = "{\"id\":\"x\",\"object\":\"chat.completion\",\"model\":\"Qwen/Qwen3-14B\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"triangleArea\",\"arguments\":\"{\\\"base\\\":3,\\\"height\\\":4}\"}}]"
                + "},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":10,\"total_tokens\":15}}";
        CapturingHttpClient client = new CapturingHttpClient(toolCallJson);
        LangChain4jModelExecutor exec = new LangChain4jModelExecutor(
                "https://api.siliconflow.cn/v1", "dummy-key",
                Map.of("enable_thinking", false),
                Duration.ofSeconds(60), null,
                new CapturingHttpClientBuilder(client));

        java.lang.reflect.Method method = com.agentdemo007.capability.tool.TriangleAreaTool.class
                .getDeclaredMethod("triangleArea", double.class, double.class);
        dev.langchain4j.agent.tool.ToolSpecification spec =
                dev.langchain4j.agent.tool.ToolSpecifications.toolSpecificationFrom(method);
        java.util.List<dev.langchain4j.data.message.ChatMessage> messages =
                java.util.List.of(new dev.langchain4j.data.message.UserMessage("底3高4的三角形面积"));

        // 工具调用路径：messages + tools（prompt 置 "PROMPT_IGNORED"——执行器有 messages 时应用 messages 而非 prompt）
        LlmRequest req = new LlmRequest("Qwen/Qwen3-14B", "PROMPT_IGNORED", 512, true, messages,
                java.util.List.of(spec));
        LlmResponse resp = exec.execute(req);

        // tool_calls-OUT：模型发起的工具调用经执行器提取进 LlmResponse.toolCalls
        assertThat(resp.toolCalls()).hasSize(1);
        assertThat(resp.toolCalls().get(0).name()).isEqualTo("triangleArea");
        assertThat(resp.toolCalls().get(0).arguments()).contains("3").contains("4");
        assertThat(resp.tokens()).isEqualTo(15);
        // tools-IN：ToolSpecification 序列化进请求体（"tools" 字段 + 工具名）
        assertThat(client.capturedBody).contains("tools");
        assertThat(client.capturedBody).contains("triangleArea");
        // messages-IN：多消息进请求体（user content="底3高4..."），prompt="PROMPT_IGNORED" 不外泄（执行器用 messages）
        assertThat(client.capturedBody).contains("底3高4的三角形面积");
        assertThat(client.capturedBody).doesNotContain("PROMPT_IGNORED");
    }
}
