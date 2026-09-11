package com.agentdemo007.web;

import com.agentdemo007.common.degradation.DegradationPhraseCenter;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineOrchestrator;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.persistence.mq.AuditProducer;
import com.agentdemo007.persistence.mq.CapturingMessagePublisher;
import com.agentdemo007.persistence.mq.ChatTurnFinalizer;
import com.agentdemo007.persistence.mq.HistoryPersistProducer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话控制器单测（Phase 12·对外收口终端 + Phase 13 异步持久化接线）。
 *
 * <p>覆盖 §5.12 终端翻译：{@link PipelineResult} → {@link UnifiedResponse}。
 * <ul>
 *   <li>同步 /chat：正常结果→success(reply)；降级结果→仍 success（话术短路不暴露技术码，HTTP 200），
 *       但 data 携带 degraded/scenario 供客户端可选展示；sessionId 缺省自动生成、提供则原样回传。</li>
 *   <li>SSE /chat/stream：单事件 text/event-stream（真实流式延后），data 行携带同样的 ChatResponse。</li>
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
        return new ChatController(stub, finalizer, objectMapper);
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
    void chatStream_returnsSseSingleEvent() {
        ChatController controller = controller(PipelineResult.ok("您好"));

        ResponseEntity<String> resp = controller.chatStream(new ChatRequest("sess-2", "你好"));

        assertThat(resp.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(resp.getBody()).startsWith("data:");
        assertThat(resp.getBody()).contains("您好");
        assertThat(resp.getBody()).contains("sess-2");
        assertThat(resp.getBody()).endsWith("\n\n");
    }

    @Test
    void chat_invokesFinalizerAfterTurn_persistsChatTurnEvent() {
        ChatController controller = controller(PipelineResult.ok("您好"));

        controller.chat(new ChatRequest("sess-1", "你好"));

        // 终端后置钩子被触发：会话持久化路由键收到 ChatTurnEvent（停 MQ→seam 假捕获，不连 broker→主接口 200）
        assertThat(publisher.publishCount()).isGreaterThanOrEqualTo(1);
        assertThat(publisher.published().get(0).getKey()).isEqualTo("session.persist");
    }
}
