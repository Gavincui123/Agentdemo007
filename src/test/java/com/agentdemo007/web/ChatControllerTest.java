package com.agentdemo007.web;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineOrchestrator;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.progress.ProgressEvent;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.persistence.mq.AuditProducer;
import com.agentdemo007.persistence.mq.CapturingMessagePublisher;
import com.agentdemo007.persistence.mq.ChatTurnFinalizer;
import com.agentdemo007.persistence.mq.HistoryPersistProducer;
import com.agentdemo007.session.MockMemberLevelService;
import com.agentdemo007.session.profile.UserProfileService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 对话控制器单测（Phase 12·对外收口终端 + Phase 13 异步持久化接线 + #136 SSE 真流式）。
 *
 * <p>覆盖 §5.12 终端翻译：{@link PipelineResult} → {@link UnifiedResponse}。
 * <ul>
 *   <li>同步 /chat：正常结果→success(reply)；降级结果→仍 success（话术短路不暴露技术码，HTTP 200），
 *       但 data 携带 degraded/scenario 供客户端可选展示；sessionId 缺省自动生成、提供则原样回传。</li>
 *   <li>SSE /chat/stream：返回 {@link SseEmitter}，工作线程跑流水线 + 进度事件实时 flush + 终端 reply_ready
 *       （#136 富事件真流式）。{@link #runToSse_emitsStepProgressThenReplyReady} 直接驱动工作线程体
 *       （{@link ChatController#runToSse} 包级 seam），用 {@link CapturingSseEmitter} 截获 send 验证多事件时序。</li>
 * </ul>
 * <p>桩 {@link PipelineOrchestrator} 固定产出，聚焦控制器翻译逻辑，不依赖真实流水线。
 * Phase 13 接线验证：终端后置钩子 {@link ChatTurnFinalizer} 经 {@link CapturingMessagePublisher} 假替换 seam
 * （不连 broker），断言 /chat 后会话持久化投递被触发（停 MQ→seam 假兜底→主接口 200）。
 */
class ChatControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 捕获 finalizer 投递，断言控制器接线（不连 broker）。 */
    private final CapturingMessagePublisher publisher = new CapturingMessagePublisher();

    private ChatController controller(PipelineResult fixedResult) {
        PipelineOrchestrator stub = new PipelineOrchestrator(List.of(), new DegradationPhraseCenter()) {
            @Override
            public PipelineResult run(PipelineContext context) {
                return fixedResult;
            }
        };
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher));
        return new ChatController(stub, finalizer, objectMapper, new SyncTaskExecutor(), new MockMemberLevelService());
    }

    @Test
    void chat_sync_returnsSuccessWithReplyAndSessionId() {
        ChatController controller = controller(PipelineResult.ok("您好，订单已查到。"));

        UnifiedResponse resp = controller.chat(new ChatRequest("sess-1", "查订单"));

        assertThat(resp.code()).isEqualTo(0);
        ChatResponse data = (ChatResponse) resp.data();
        assertThat(data.sessionId()).isEqualTo("sess-1");
        assertThat(data.reply()).isEqualTo("您好，订单已查到。");
        assertThat(data.degraded()).isFalse();
        assertThat(data.scenario()).isNull();
    }

    @Test
    void chat_recordsChatRequestMetric_oncePerRequest() {
        // 2026-09-17 回归钉：recordChatRequest 此前全工程零调用点——可观测台「对话请求」恒 0，
        // /obs 页被 hasData 门控误判"尚无流量"。同步/流式都汇经 run()，恰好计数一次。
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PipelineOrchestrator stub = new PipelineOrchestrator(List.of(), new DegradationPhraseCenter()) {
            @Override
            public PipelineResult run(PipelineContext context) {
                return PipelineResult.ok("好的。");
            }
        };
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher));
        ChatController controller = new ChatController(
                stub, finalizer, objectMapper, new SyncTaskExecutor(), 120_000L, new AgentMetrics(registry),
                new MockMemberLevelService(), null);

        controller.chat(new ChatRequest("sess-m", "你好"));

        assertThat(registry.counter("agent.chat.requests").count()).isEqualTo(1.0);
    }

    @Test
    void chat_carriesRagCitationsToResponse() {
        ChatController controller = controller(
                PipelineResult.ok("退款流程如下", List.of("[来源: kb-refund] 退款流程说明")));

        UnifiedResponse resp = controller.chat(new ChatRequest("sess-c", "退款"));

        // Phase 20 citation：result.citations → ChatResponse.citations 透传（回答带来源，可追溯）
        ChatResponse data = (ChatResponse) resp.data();
        assertThat(data.citations()).containsExactly("[来源: kb-refund] 退款流程说明");
    }

    @Test
    void chat_sync_degradedStillSuccessNoErrorCodeExposed() {
        ChatController controller = controller(
                PipelineResult.degraded("我暂时无法回应，请稍后重试。", DegradationScenario.MODEL_DOWN));

        UnifiedResponse resp = controller.chat(new ChatRequest("sess-1", "你好"));

        assertThat(resp.code()).isEqualTo(0); // 话术短路：code 恒 0，不暴露技术码
        ChatResponse data = (ChatResponse) resp.data();
        assertThat(data.degraded()).isTrue();
        assertThat(data.scenario()).isEqualTo("MODEL_DOWN");
        assertThat(data.reply()).contains("暂时无法回应");
    }

    @Test
    void chat_sessionIdGeneratedWhenAbsentOrBlank() {
        ChatController controller = controller(PipelineResult.ok("ok"));

        ChatResponse absent = (ChatResponse) controller.chat(new ChatRequest(null, "你好")).data();
        ChatResponse blank = (ChatResponse) controller.chat(new ChatRequest("   ", "你好")).data();

        assertThat(absent.sessionId()).isNotBlank();
        assertThat(blank.sessionId()).isNotBlank();
        assertThat(absent.sessionId()).isNotEqualTo(blank.sessionId());
    }

    @Test
    void runToSse_emitsStepProgressThenReplyReady() {
        // 桩：run 时经 context.emitter() 发进度事件（编排器/各步在真流水线会发），终端返 ok("您好")
        PipelineOrchestrator stub = new PipelineOrchestrator(List.of(), new DegradationPhraseCenter()) {
            @Override
            public PipelineResult run(PipelineContext context) {
                context.emitter().emit(new ProgressEvent.StepStarted("triage"));
                context.emitter().emit(new ProgressEvent.StepFinished("triage", ProgressEvent.Outcome.PROCEED, null));
                return PipelineResult.ok("您好");
            }
        };
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher));
        ChatController controller = new ChatController(stub, finalizer, objectMapper, new SyncTaskExecutor(), new MockMemberLevelService());

        CapturingSseEmitter sse = new CapturingSseEmitter();
        controller.runToSse(sse, new ChatRequest("sess-2", "你好"));

        // 进度事件（step_started/step_finished）+ 终端 reply_ready 全经 SseEmitter.send 截获；
        // reply_ready 负载 = ChatResponse JSON（含 reply "您好" + sessionId "sess-2"）
        String all = String.join("", sse.captured);
        assertThat(all).contains("step_started");
        assertThat(all).contains("step_finished");
        assertThat(all).contains("reply_ready");
        assertThat(all).contains("您好");
        assertThat(all).contains("sess-2");
    }

    @Test
    void timeoutFallback_sendsDegradedReplyReadyAndCompletes() {
        // 超时治理：SSE 超时后 onTimeout 补发 reply_ready（PIPELINE_TIMEOUT 话术，终态与正常路径同形）+ complete
        ChatController controller = controller(PipelineResult.ok("您好"));
        CapturingSseEmitter sse = new CapturingSseEmitter();

        controller.sendTimeoutFallback(sse, "sess-t");

        String all = String.join("", sse.captured);
        assertThat(all).contains("reply_ready");
        assertThat(all).contains(DegradationScenario.PIPELINE_TIMEOUT.phrase());
        assertThat(all).contains("PIPELINE_TIMEOUT");
        assertThat(all).contains("sess-t");
        assertThat(all).contains("true"); // degraded=true
        assertThat(all).contains("PIPELINE_TIMEOUT");
        assertThat(all).contains("totalMs"); // 超时兜底也带计时
        assertThat(sse.completed).isTrue();
    }

    @Test
    void runToSse_emitsFirstTokenAndTotalTimingInReplyReady() {
        // 响应时间统计：桩发 TokenChunk → firstTokenMs 记录；终端 reply_ready JSON 带 totalMs + firstTokenMs
        PipelineOrchestrator stub = new PipelineOrchestrator(List.of(), new DegradationPhraseCenter()) {
            @Override
            public PipelineResult run(PipelineContext context) {
                context.emitter().emit(new ProgressEvent.TokenChunk("你"));
                context.emitter().emit(new ProgressEvent.TokenChunk("好"));
                return PipelineResult.ok("你好");
            }
        };
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher));
        ChatController controller = new ChatController(stub, finalizer, objectMapper, new SyncTaskExecutor(), new MockMemberLevelService());

        CapturingSseEmitter sse = new CapturingSseEmitter();
        controller.runToSse(sse, new ChatRequest("sess-timing", "你好"));

        String all = String.join("", sse.captured);
        assertThat(all).contains("reply_ready");
        assertThat(all).contains("\"totalMs\"");
        assertThat(all).contains("\"firstTokenMs\""); // TokenChunk 已发 → 首字计时非 null
    }

    @Test
    void runToSse_deadFlagSet_skipsTerminalReplyReady() {
        // 超时治理：流水线完成时 SSE 已超时（dead 置位）→ 晚到的成功不再投递（不撞 already completed）
        PipelineOrchestrator stub = new PipelineOrchestrator(List.of(), new DegradationPhraseCenter()) {
            @Override
            public PipelineResult run(PipelineContext context) {
                context.emitter().emit(new ProgressEvent.StepStarted("triage"));
                return PipelineResult.ok("您好");
            }
        };
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher));
        ChatController controller = new ChatController(stub, finalizer, objectMapper, new SyncTaskExecutor(), new MockMemberLevelService());

        CapturingSseEmitter sse = new CapturingSseEmitter();
        controller.runToSse(sse, new ChatRequest("sess-d", "你好"), "sess-d",
                new java.util.concurrent.atomic.AtomicBoolean(true));

        // dead 置位：进度事件静默丢弃 + 终端 reply_ready 不发 + 不 complete（超时话术已由 onTimeout 补发）
        assertThat(sse.captured).isEmpty();
        assertThat(sse.completed).isFalse();
    }

    @Test
    void chat_invokesFinalizerAfterTurn_persistsChatTurnEvent() {
        ChatController controller = controller(PipelineResult.ok("您好"));

        controller.chat(new ChatRequest("sess-1", "你好"));

        // 终端后置钩子被触发：会话持久化路由键收到 ChatTurnEvent（停 MQ→seam 假捕获，不连 broker→主接口 200）
        assertThat(publisher.publishCount()).isGreaterThanOrEqualTo(1);
        assertThat(publisher.published().get(0).getKey()).isEqualTo("session.persist");
    }

    @Test
    void resetProfile_success_returnsResetTrue() {
        // T102 遗忘权正常路径：结构化 200 + reset:true
        ChatController controller = controllerWithProfileService(mock(UserProfileService.class));

        UnifiedResponse resp = controller.resetProfile(new ChatRequest("sess-r", "忘记我", "u-9"));

        assertThat(resp.code()).isEqualTo(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.data();
        assertThat(data.get("reset")).isEqualTo(true);
        assertThat(data.get("userId")).isEqualTo("u-9");
    }

    @Test
    void resetProfile_storeThrows_structuredFailure_notGlobal5xx() {
        // 2026-09-21 review 修订：存储异常收口为 200 + {reset:false, reason}（不落全局 500，客户端可重试）
        UserProfileService failing = mock(UserProfileService.class);
        doThrow(new RuntimeException("redis down")).when(failing).reset("u-9");
        ChatController controller = controllerWithProfileService(failing);

        UnifiedResponse resp = controller.resetProfile(new ChatRequest("sess-r", "忘记我", "u-9"));

        assertThat(resp.code()).isEqualTo(0); // HTTP 语义仍 200，失败在响应体结构化表达
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.data();
        assertThat(data.get("reset")).isEqualTo(false);
        assertThat((String) data.get("reason")).contains("稍后重试");
    }

    /** 构造带画像服务的控制器（遗忘权端点两态测试用；其余依赖与 {@link #controller} 相同）。 */
    private ChatController controllerWithProfileService(UserProfileService profileService) {
        PipelineOrchestrator stub = new PipelineOrchestrator(List.of(), new DegradationPhraseCenter()) {
            @Override
            public PipelineResult run(PipelineContext context) {
                return PipelineResult.ok("ok");
            }
        };
        ChatTurnFinalizer finalizer = new ChatTurnFinalizer(
                new HistoryPersistProducer(publisher), new AuditProducer(publisher));
        return new ChatController(stub, finalizer, objectMapper, new SyncTaskExecutor(),
                new MockMemberLevelService(), profileService);
    }

    /** 截获 {@link SseEmitter#send(SseEventBuilder)}：build() 返回 Set<DataWithMediaType>，逐项取 data 累积。 */
    static final class CapturingSseEmitter extends SseEmitter {
        final List<String> captured = new ArrayList<>();
        boolean completed = false;

        @Override
        public void send(SseEmitter.SseEventBuilder builder) {
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                if (item.getData() != null) {
                    captured.add(item.getData().toString());
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
