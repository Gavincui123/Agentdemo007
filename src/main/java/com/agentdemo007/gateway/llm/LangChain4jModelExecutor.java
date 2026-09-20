package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.StreamingReplyHandler;
import com.agentdemo007.gateway.exception.LlmUnavailableException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j 实现的 {@link ModelExecutor}——把 {@link LlmRequest} 翻译成 {@link OpenAiChatModel} 调用，
 * 退役自研 {@code OpenAiModelExecutor}-HTTP（请求序列化/响应解析/超时/重试全归 LC4j；本类只做
 * LlmRequest↔ChatModel 翻译 + 关思考参数注入 + 空 content/空 key 守卫）。
 *
 * <p>每 provider 一个实例（由 {@code LlmConfig} 按 {@code llm.providers[*]} 注入 baseUrl/apiKey/disableThinkingParams），
 * modelId/maxTokens/disableThinking 每请求由 {@link LlmRequest} 携带（意图驱动——闲聊恒关思考、其他由
 * {@code llm.thinking.enabled} 开关定，经 {@link ChatLlmService} 收口透传）。
 *
 * <p>翻译契约（镜像旧 {@code OpenAiModelExecutor} 语义，零行为漂移）：
 * <ul>
 *   <li>{@code request.modelId()} → {@code modelName}（请求体 model 字段）</li>
 *   <li>{@code request.prompt()} → {@code UserMessage}（请求体 messages[user].content）</li>
 *   <li>{@code request.maxTokens()>0} → {@code maxTokens}（请求体 max_tokens）</li>
 *   <li>{@code request.disableThinking()=true} 且 {@code disableThinkingParams} 非空 → {@code customParameters} 注入
 *       （SF {@code enable_thinking=false}）；{@code disableThinking=false} 不注入（避免非推理模型 400）。
 *       与旧执行器同语义：不硬编码参数名，只合并 provider 配置键值，使不同 provider 各填各的参数。</li>
 *   <li>空 content → 抛 {@link LlmUnavailableException}（推理模型预算耗尽 content 空；不外泄思考过程）</li>
 *   <li>空 api-key → 抛 {@link LlmUnavailableException}（provider 未配 key 即降级话术，与 Noop 占位等价端态）</li>
 *   <li>HTTP 4xx/5xx/超时等 {@link RuntimeException} 原样上抛，由 {@code FailoverExecutor} triage 决定重试/转移</li>
 *   <li>{@code tokenUsage.totalTokenCount()} → {@link LlmResponse#tokens()}（
 *       {@link com.agentdemo007.gateway.core.UnifiedModelGateway} 预算记账需要真实 tokens，非 0）</li>
 * </ul>
 *
 * <p>httpClientBuilder 可空：null → {@link OpenAiChatModel} 内置默认 JDK 客户端（生产真打 SF，经
 * {@code OpenAiChatModelSiliconFlowSmokeTest} 真冒烟坐实）；非空 → 注入测试假 transport（无网络恒 GREEN）。
 *
 * <p>关联 [[langchain4j-boot4-compat-findings]] [[dont-hardwrite-use-dep-methods]] [[phase4-gateway-design]]
 * [[phase-llm-primary-backup-breaker]]（关思考铁律落地路径：闲聊/决策 disableThinking=true→enable_thinking=false）。
 */
public class LangChain4jModelExecutor implements ModelExecutor {

    private final String baseUrl;
    private final String apiKey;
    private final Map<String, Object> disableThinkingParams;
    private final Duration timeout;
    /** 采样温度（provider 配置 {@code temperature}，默认 0.2 准确优先）；null=不设置走 provider 默认。 */
    private final Double temperature;
    private final HttpClientBuilder httpClientBuilder;

    /** 生产构造器：httpClientBuilder=null → OpenAiChatModel 默认 JDK 客户端（真打 SF）。 */
    public LangChain4jModelExecutor(String baseUrl, String apiKey, Map<String, Object> disableThinkingParams,
                                   Duration timeout, Double temperature) {
        this(baseUrl, apiKey, disableThinkingParams, timeout, temperature, null);
    }

    /** 测试/生产构造器：httpClientBuilder 非空 → 注入（假 transport 测试 / 自定义 transport）。 */
    public LangChain4jModelExecutor(String baseUrl, String apiKey, Map<String, Object> disableThinkingParams,
                                   Duration timeout, Double temperature, HttpClientBuilder httpClientBuilder) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.disableThinkingParams = disableThinkingParams == null ? Map.of() : disableThinkingParams;
        this.timeout = timeout;
        this.temperature = temperature;
        this.httpClientBuilder = httpClientBuilder;
    }

    @Override
    public LlmResponse execute(LlmRequest request) {
        // 空 key → provider 未配——前置校验不发请求（与 Noop 占位等价端态：未配 key 即降级话术）
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmUnavailableException("LLM api-key 未配置（baseUrl=" + baseUrl + "）", null);
        }

        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(request.modelId())
                .timeout(timeout != null ? timeout : Duration.ofSeconds(60))
                // LC4j 内部盲重试（默认 2 次、不退避、不感知熔断/SSE 预算）关闭——重试/转移归网关层
                // （ResilientExecutor 同模型退避 + FailoverExecutor 主备切换）。实测事故：120s 超时 ×
                // LC4j 3 次尝试把单次逻辑调用放大到 121s+，吃满 SSE 窗口后连接被掐、兜底话术发不出去。
                .maxRetries(0);
        if (request.maxTokens() > 0) {
            b.maxTokens(request.maxTokens());
        }
        if (temperature != null) {
            b.temperature(temperature); // 采样温度（provider 配置，默认 0.2 准确优先）
        }
        // 关思考：disableThinking=true 且 provider 配了关思考参数 → customParameters 注入（SF enable_thinking=false 等）。
        // disableThinking=false 不注入——避免对不支持该参数的 provider 报 400（SF 非推理模型 400 code=20015）。
        // 不硬编码参数名，只合并 provider 配置键值，使不同 provider 各填各的参数（SenseNova reasoning_effort 等）。
        if (request.disableThinking() && !disableThinkingParams.isEmpty()) {
            b.defaultRequestParameters(OpenAiChatRequestParameters.builder()
                    .customParameters(disableThinkingParams)
                    .build());
        }
        if (httpClientBuilder != null) {
            b.httpClientBuilder(httpClientBuilder); // 测试假 transport / 自定义 transport
        }
        OpenAiChatModel model = b.build();

        // ChatRequest 载 messages：工具路径用 request.messages()（多轮含 tool-result 回传 + tools 经下行 tool-spec），
        // plain-chat 路径 messages 空→回退 prompt 作单条 UserMessage。maxTokens + customParameters 经 model 的
        // defaultRequestParameters 合并进请求体（body-capture 已坐实 messages-only ChatRequest 仍应用 model 默认参数）。
        ChatRequest.Builder chatReqB = ChatRequest.builder();
        if (request.messages() != null && !request.messages().isEmpty()) {
            chatReqB.messages(request.messages()); // 工具路径：多消息（含 ToolExecutionResultMessage 回传）
        } else {
            chatReqB.messages(new UserMessage(request.prompt())); // plain-chat：单串 prompt
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            chatReqB.toolSpecifications(request.tools()); // tools-IN：序列化进请求体 "tools" 字段
        }
        ChatResponse resp = model.chat(chatReqB.build());

        // content 可空（模型发起 tool_call 时 content=null，产出在 tool_calls）——取 toolCalls 作有效产出。
        String content = resp.aiMessage().text();
        List<ToolExecutionRequest> toolCalls = resp.aiMessage().toolExecutionRequests();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();
        // 空 content 且无 tool_calls = 模型失败（推理模型 max_tokens 不足时 reasoning_content 耗尽预算、content 空 finish_reason=length）。
        // 空串对用户无价值且掩盖「未产出答案」，故抛 LlmUnavailableException（分诊 NON_RETRYABLE_CLIENT→计熔断+故障转移切备）；
        // 不回退读 reasoning_content（思考过程非最终答案，外泄会污染回复）。有 tool_calls 时即使 content 空也属有效产出（不抛）。
        if ((content == null || content.isBlank()) && !hasToolCalls) {
            throw new LlmUnavailableException(
                    "LLM 返回空 content 且无 tool_calls（modelId=" + request.modelId() + "）", null);
        }

        int tokens = 0;
        if (resp.tokenUsage() != null && resp.tokenUsage().totalTokenCount() != null) {
            tokens = resp.tokenUsage().totalTokenCount();
        }
        return new LlmResponse(request.modelId(), content != null ? content : "", tokens,
                toolCalls != null ? toolCalls : List.of());
    }

    /**
     * 流式执行（[[q2-token-streaming]]）：建 {@link OpenAiStreamingChatModel}（镜像 {@link #execute} 建
     * {@link OpenAiChatModel} 的翻译：baseUrl/apiKey/modelName/timeout/maxTokens/disableThinking→customParameters
     * + httpClientBuilder seam），{@code doChat(ChatRequest, lc4jHandler)} 逐 token 回调。
     *
     * <p>把引擎无关 {@link StreamingReplyHandler} 适配成 LC4j {@link StreamingChatResponseHandler}
     * （onPartialResponse(String)→逐 token；onCompleteResponse(ChatResponse)→全文+tokens；onError→透传）。
     * 流式<b>主模型 only、无中途故障转移</b>（{@link com.agentdemo007.gateway.llm.ChatLlmService#chatRawStream}
     * 不经 FailoverExecutor）；onError→调用方 OutputStep 回退阻塞 chatRaw。空 key→抛 LlmUnavailable（同步→onError）。
     */
    @Override
    public void stream(LlmRequest request, StreamingReplyHandler handler) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmUnavailableException("LLM api-key 未配置（baseUrl=" + baseUrl + "）", null);
        }
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder b = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(request.modelId())
                .timeout(timeout != null ? timeout : Duration.ofSeconds(60));
        if (httpClientBuilder != null) {
            b.httpClientBuilder(httpClientBuilder);
        }
        OpenAiStreamingChatModel model = b.build();

        // per-request OpenAiChatRequestParameters（修 ClassCast + 坑②）：OpenAiStreamingChatModel.doChat 强转
        // ChatRequest.parameters()→OpenAiChatRequestParameters，故不能 ChatRequest.builder().messages().build()
        // （产 DefaultChatRequestParameters→ClassCastException，用户实测报"流式失败回退阻塞"——doChat:149）。
        // 且 per-request params 覆盖 model defaultRequestParameters（坑②坐实：OpenAiChatModelBodyCaptureTest
        // clientA 缺 modelName→body 缺 model→SF 20015；clientC 带 modelName→body 有 model）→须自包含：
        // modelName（否则 body 缺 model）+ maxOutputTokens（否则 max_tokens 丢）+ customParameters（关思考）。
        OpenAiChatRequestParameters.Builder paramsB = OpenAiChatRequestParameters.builder()
                .modelName(request.modelId());
        if (request.maxTokens() > 0) {
            paramsB.maxOutputTokens(request.maxTokens());
        }
        if (temperature != null) {
            paramsB.temperature(temperature); // 流式与阻塞同口径（provider 配置，默认 0.2）
        }
        if (request.disableThinking() && !disableThinkingParams.isEmpty()) {
            paramsB.customParameters(disableThinkingParams);
        }
        OpenAiChatRequestParameters params = paramsB.build();

        ChatRequest.Builder chatReqB = ChatRequest.builder().parameters(params);
        if (request.messages() != null && !request.messages().isEmpty()) {
            chatReqB.messages(request.messages());
        } else {
            chatReqB.messages(new UserMessage(request.prompt()));
        }
        model.doChat(chatReqB.build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                handler.onPartialResponse(token);
            }
            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                String content = completeResponse.aiMessage().text();
                int tokens = (completeResponse.tokenUsage() != null
                        && completeResponse.tokenUsage().totalTokenCount() != null)
                        ? completeResponse.tokenUsage().totalTokenCount() : 0;
                handler.onCompleteResponse(content != null ? content : "", tokens);
            }
            @Override
            public void onError(Throwable error) {
                handler.onError(error);
            }
        });
    }
}
