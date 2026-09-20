package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.StreamingReplyHandler;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LangChain4jModelExecutor#stream} 请求体捕获单测（[[q2-token-streaming]] 流式 ClassCast 修正）。
 *
 * <p>**无网络、无真实 key、恒 GREEN**：插假 {@link HttpClient}（LC4j transport seam，测试桩合规——
 * [[dont-hardwrite-use-dep-methods]] 豁免 test double）实现<b>流式</b> {@code execute(req, parser, listener)}，
 * 捕获 {@link HttpRequest#body()}（真 {@link dev.langchain4j.model.openai.OpenAiStreamingChatModel} 全序列化产出的 JSON），
 * 喂罐装 OpenAI streaming SSE chunk 驱动 {@link StreamingReplyHandler} 回调。
 *
 * <p>**修的 bug**（用户实测报）：{@code OpenAiStreamingChatModel.doChat} 强转
 * {@code ChatRequest.parameters()→OpenAiChatRequestParameters}，而 {@code ChatRequest.builder().messages().build()}
 * 产 {@code DefaultChatRequestParameters}→{@code ClassCastException}→OutputStep 全量回退阻塞（无 token 流）。
 * 修=per-request 显式建 {@link dev.langchain4j.model.openai.OpenAiChatRequestParameters}（自包含 modelName +
 * maxOutputTokens + customParameters，坑②：per-request 覆盖 model default 须带全）设 {@code .parameters(...)}。
 *
 * <p>阻塞 {@code execute} 不中招因 {@code OpenAiChatModel.chat} 不强转；唯流式 {@code doChat} 强转。
 * 关联 [[business-tools-workflow-dag]] 坑①坑②、[[q2-token-streaming]]、[[dont-hardwrite-use-dep-methods]]。
 */
class LangChain4jModelExecutorStreamingBodyCaptureTest {

    private static final String SF_BASE = "https://api.siliconflow.cn/v1";
    private static final String MODEL = "Qwen/Qwen3-14B";

    /** 假 transport：实现流式 execute 捕 body + 喂罐装 OpenAI SSE chunk，阻塞 execute 不用于本测。 */
    static final class StreamingCapturingClient implements HttpClient {
        String capturedBody;
        String capturedUrl;

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("streaming test - blocking execute 不应被调");
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            this.capturedBody = request.body();
            this.capturedUrl = request.url();
            // 罐装 OpenAI chat-completion streaming 响应：1 个 delta content chunk + 1 个 stop+usage chunk + [DONE]
            String sse =
                    "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"" + MODEL + "\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"model\":\"" + MODEL + "\","
                            + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}\n\n"
                    + "data: [DONE]\n\n";
            try {
                listener.onOpen(SuccessfulHttpResponse.builder().statusCode(200).body("").build());
                parser.parse(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)), listener);
                listener.onClose();
            } catch (Exception e) {
                listener.onError(e);
            }
        }
    }

    /** 假 HttpClientBuilder：build() 返假 transport，超时返安全默认。 */
    static final class StreamingCapturingBuilder implements HttpClientBuilder {
        private final StreamingCapturingClient client;

        StreamingCapturingBuilder(StreamingCapturingClient client) {
            this.client = client;
        }

        @Override public Duration connectTimeout() { return Duration.ofSeconds(60); }
        @Override public HttpClientBuilder connectTimeout(Duration t) { return this; }
        @Override public Duration readTimeout() { return Duration.ofSeconds(60); }
        @Override public HttpClientBuilder readTimeout(Duration t) { return this; }
        @Override public HttpClient build() { return client; }
    }

    @Test
    void stream_buildsOpenAiChatRequestParameters_avoidsClassCast_andSerializesBody() {
        StreamingCapturingClient client = new StreamingCapturingClient();
        // disableThinking=true + provider disableThinkingParams={enable_thinking:false} → 须注入 body
        LangChain4jModelExecutor exec = new LangChain4jModelExecutor(SF_BASE, "dummy-key",
                Map.of("enable_thinking", false), Duration.ofSeconds(60), null, new StreamingCapturingBuilder(client));

        List<String> tokens = new ArrayList<>();
        AtomicReference<String> full = new AtomicReference<>();
        AtomicReference<Integer> tokCount = new AtomicReference<>(0);
        AtomicReference<Throwable> err = new AtomicReference<>();
        StreamingReplyHandler handler = new StreamingReplyHandler() {
            @Override public void onPartialResponse(String token) { tokens.add(token); }
            @Override public void onCompleteResponse(String fullReply, int tokens) { full.set(fullReply); tokCount.set(tokens); }
            @Override public void onError(Throwable error) { err.set(error); }
        };

        Throwable thrown = null;
        try {
            // disableThinking=true → 期望 enable_thinking=false 注入 body；maxTokens=1024 → max_tokens
            exec.stream(new LlmRequest(MODEL, "hi", 1024, true), handler);
        } catch (Throwable t) {
            thrown = t;
        }

        // 修前：doChat 强转 DefaultChatRequestParameters→OpenAiChatRequestParameters→ClassCastException（sync throw）。
        // 修后（per-request OpenAiChatRequestParameters）：cast 通过→execute 被调→body 捕到。
        assertThat(thrown).as("不应抛 ClassCastException（修：per-request OpenAiChatRequestParameters）").isNull();
        assertThat(client.capturedBody).as("流式 execute 被调（doChat cast 通过，非默认 UOE 回退）").isNotNull();
        // 坑②：per-request params 覆盖 model default→model 字段须在 per-request 带，否则 body 缺 model→SF 20015
        assertThat(client.capturedBody).contains(MODEL);
        // 关思考铁律：disableThinking=true→enable_thinking=false 进 body 顶层
        assertThat(client.capturedBody).contains("enable_thinking");
        assertThat(client.capturedBody).contains("false");
        // maxOutputTokens→max_tokens 透传
        assertThat(client.capturedBody).contains("max_tokens");
        assertThat(client.capturedBody).contains("1024");
        // 回调不应出错（SSE 喂通则 onComplete，非 onError）
        assertThat(err.get()).as("不应 onError：reason=%s", String.valueOf(err.get())).isNull();
    }
}
