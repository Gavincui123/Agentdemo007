package com.agentdemo007.web;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.progress.ProgressEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * SseProgressEmitter 桥接测（#136 富事件 SSE 真流式·[[routeplan-design]]·Slice 4）。
 *
 * <p>桥接把 {@link ProgressEvent} 翻成 SSE event：{@code event:<name>\ndata:<json>\n\n}。
 * {@code name} = variant→snake_case；{@code json} = ObjectMapper 序列化 record（prod @Bean 带
 * {@code non_null} 省 null scenario；本测用裸 JsonMapper，只钉在场字段，不钉 null 省略——配置相关）。
 * {@code send} 抛 {@link IOException} → 吞（进度 best-effort，不反噬流水线收口）。
 *
 * <p>{@code emit()} 的 send 委托在 Slice 5 ChatController 集成测覆盖（真 SseEmitter + MockMvc async）；
 * 本 slice 钉可测的纯函数 {@code eventName}/{@code jsonData} + 异常吞咽语义。
 */
class SseProgressEmitterTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void eventName_mapsVariantsToSnakeCase() {
        SseProgressEmitter emitter = new SseProgressEmitter(mock(SseEmitter.class), objectMapper);

        assertThat(emitter.eventName(new ProgressEvent.StepStarted("triage"))).isEqualTo("step_started");
        assertThat(emitter.eventName(new ProgressEvent.StepFinished("triage",
                ProgressEvent.Outcome.PROCEED, null))).isEqualTo("step_finished");
    }

    @Test
    void jsonData_serializesRecordFields() {
        SseProgressEmitter emitter = new SseProgressEmitter(mock(SseEmitter.class), objectMapper);

        String json = emitter.jsonData(new ProgressEvent.StepFinished("rag",
                ProgressEvent.Outcome.DEGRADE, DegradationScenario.RAG_SKIP));

        assertThat(json).contains("\"step\":\"rag\"");
        assertThat(json).contains("\"outcome\":\"DEGRADE\"");
        assertThat(json).contains("\"scenario\":\"RAG_SKIP\"");
    }

    @Test
    void jsonData_carriesOutcomeForProceed() {
        SseProgressEmitter emitter = new SseProgressEmitter(mock(SseEmitter.class), objectMapper);

        String json = emitter.jsonData(new ProgressEvent.StepFinished("route",
                ProgressEvent.Outcome.PROCEED, null));

        assertThat(json).contains("\"step\":\"route\"");
        assertThat(json).contains("\"outcome\":\"PROCEED\"");
        // scenario 为 null：prod @Bean(non_null) 省略；裸 JsonMapper 保留 null——只钉在场字段
    }

    @Test
    void emit_sendThrowsIOException_isSwallowedNotPropagated() throws IOException {
        SseEmitter sse = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(sse)
                .send(any(SseEmitter.SseEventBuilder.class));
        SseProgressEmitter emitter = new SseProgressEmitter(sse, objectMapper);

        // 进度发射失败不得反噬流水线（best-effort，吞 + 记日志）
        assertThatCode(() -> emitter.emit(new ProgressEvent.StepStarted("triage")))
                .doesNotThrowAnyException();
    }

    // ---- [[q2-token-streaming]] 逐 token 流式：TokenChunk → reply_chunk SSE 事件 ----

    @Test
    void eventName_mapsTokenChunkToReplyChunk() {
        SseProgressEmitter emitter = new SseProgressEmitter(mock(SseEmitter.class), objectMapper);

        assertThat(emitter.eventName(new ProgressEvent.TokenChunk("你好")))
                .isEqualTo("reply_chunk");
    }

    @Test
    void jsonData_carriesTokenChunkText() {
        SseProgressEmitter emitter = new SseProgressEmitter(mock(SseEmitter.class), objectMapper);

        String json = emitter.jsonData(new ProgressEvent.TokenChunk("你好"));

        assertThat(json).contains("\"text\":\"你好\"");
    }

    // ---- 超时治理：dead 标志置位（SSE 超时/断开/完成）→ 进度事件静默丢弃 ----

    @Test
    void emit_deadFlagSet_skipsSendSilently() throws IOException {
        // 超时后流水线继续推进：dead 置位 → 不再撞 already completed 刷 WARN，静默 no-op
        SseEmitter sse = mock(SseEmitter.class);
        SseProgressEmitter emitter = new SseProgressEmitter(sse, objectMapper,
                new java.util.concurrent.atomic.AtomicBoolean(true));

        emitter.emit(new ProgressEvent.StepStarted("triage"));
        emitter.emit(new ProgressEvent.TokenChunk("你好"));

        verifyNoInteractions(sse);
    }
}
