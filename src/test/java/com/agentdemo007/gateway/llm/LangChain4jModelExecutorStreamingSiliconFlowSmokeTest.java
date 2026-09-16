package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.config.LlmProperties;
import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.core.StreamingReplyHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ① wiring 真 SiliconFlow <b>流式</b>冒烟（env-gated·[[q2-token-streaming]]）：验
 * {@link LangChain4jModelExecutor#stream} 经 {@link LlmConfig#buildRoutingExecutor} 装配后，真打 SF 流式端点，
 * 逐 token 经 {@link StreamingReplyHandler#onPartialResponse} 回调 + 终态 {@code onCompleteResponse}（全文+tokens）。
 *
 * <p>与 {@link LangChain4jModelExecutorSiliconFlowSmokeTest}（阻塞 {@code execute}）互补：本冒烟验<b>流式 token 透传</b>
 * ——OutputStep 经 chatRawStream→gateway.stream→executor.stream 到本 leaf，逐 token 发 reply_chunk SSE。
 *
 * <p><b>运行方式</b>（密钥经环境变量注入，不落明文）：
 * <pre>
 * export SF_KEY=sk-你的SiliconFlow密钥
 * export JAVA_HOME=/Users/cuizhifeng/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home
 * ./mvnw -Dtest=LangChain4jModelExecutorStreamingSiliconFlowSmokeTest test
 * </pre>
 * 无 SF_KEY 时自动跳过（{@link EnabledIfEnvironmentVariable}），不影响日常 GREEN 基线。
 *
 * <p>用 {@code Qwen/Qwen3-14B} + {@code disableThinking=true}（关思考铁律）。{@code doChat} 流式可能异步，
 * 故用 {@link CountDownLatch} 等终态（onComplete/onError），60s 超时。
 * 关联 [[langchain4j-boot4-compat-findings]] [[phase-llm-primary-backup-breaker]] [[q2-token-streaming]]。
 *
 * <p>已知限制：leaf 的 LC4j SSE→handler 映射真打验（SSE-fake 单测因 ServerSentEvent event-field/[DONE] 语义
 * 不确定未做，real-SF 冒烟为权威验证，[[q2-token-streaming]]）。
 */
@EnabledIfEnvironmentVariable(named = "SF_KEY", matches = "sk-.+", disabledReason = "缺 SF_KEY：跳过流式真冒烟")
class LangChain4jModelExecutorStreamingSiliconFlowSmokeTest {

    private static final String SF_BASE = "https://api.siliconflow.cn/v1";
    private static final String SF_MODEL = "Qwen/Qwen3-14B";
    private static final String PROMPT = "用一句话自我介绍";

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
    void stream_realSfEmitsTokensAndCompletes() throws Exception {
        ModelExecutor exec = LlmConfig.buildRoutingExecutor(props(System.getenv("SF_KEY")));
        List<String> tokens = new ArrayList<>();
        AtomicReference<String> full = new AtomicReference<>();
        AtomicReference<Integer> tokCount = new AtomicReference<>(0);
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        StreamingReplyHandler handler = new StreamingReplyHandler() {
            @Override public void onPartialResponse(String token) { tokens.add(token); }
            @Override public void onCompleteResponse(String fullReply, int tokens) {
                full.set(fullReply);
                tokCount.set(tokens);
                latch.countDown();
            }
            @Override public void onError(Throwable error) {
                err.set(error);
                latch.countDown();
            }
        };

        exec.stream(new LlmRequest("siliconflow-large", PROMPT, 1024, true), handler);
        boolean completed = latch.await(60, TimeUnit.SECONDS);

        assertThat(err.get())
                .as("流式不应出错：reason=%s", String.valueOf(err.get()))
                .isNull();
        assertThat(completed).as("60s 内须终态（onComplete/onError）").isTrue();
        assertThat(tokens).as("须至少 1 个 token").isNotEmpty();
        assertThat(full.get()).as("全文须非空").isNotBlank();
        System.out.printf("[流式冒烟] tokens=%d full=%s totalTokens=%d%n",
                tokens.size(), full.get(), tokCount.get());
    }
}
