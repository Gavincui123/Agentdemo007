package com.agentdemo007.web;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineExecutor;
import com.agentdemo007.common.pipeline.PipelineResult;
import com.agentdemo007.common.response.UnifiedResponse;
import com.agentdemo007.persistence.mq.ChatTurnFinalizer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * 对话控制器（Phase 12·对外收口终端 + Phase 13 异步持久化接线）。
 *
 * <p>把 {@link PipelineResult} 翻译为 {@link UnifiedResponse}——任何流水线产出（正常/降级/短路）
 * 均 HTTP 200 + {@code code=0}（话术短路不暴露技术码），降级信息经 {@link ChatResponse} 的
 * {@code degraded}/{@code scenario} 元字段透出。与接入层 {@code InputSecurityFilter}/
 * {@code ValidationFilter} 的短路数据形状一致（第四原则·对外收口：终端与边界同形）。
 *
 * <p>Phase 13：流水线结束后调 {@link ChatTurnFinalizer#finalizeTurn} 收口会话持久化 + 审计刷出
 * （best-effort，停 MQ 不影响主接口 200）；该副作用经 seam 投递，不阻塞响应。
 *
 * <p>Phase 14：依赖 {@link PipelineExecutor} 接口而非具体编排器——由 {@code LangGraphConfig} 按
 * {@code agentdemo.pipeline.mode=linear|graph} 选择实现注入（线性 {@code PipelineOrchestrator}
 * 或图 {@code GraphExecutor}），控制器对此无感（④统一收口：换引擎不换出口）。
 *
 * <ul>
 *   <li>{@code POST /chat} — 同步：返回完整 {@link UnifiedResponse}。</li>
 *   <li>{@code POST /chat/stream} — SSE：单事件 {@code text/event-stream}（{@code data:} 行携带
 *       同样的 {@link ChatResponse} JSON）；真实 token 流式在接入 LangChain4j 流式契约后扩展，
 *       出口形状（单事件 ChatResponse）不变。</li>
 * </ul>
 */
@RestController
public class ChatController {

    private final PipelineExecutor pipelineExecutor;
    private final ChatTurnFinalizer finalizer;
    private final ObjectMapper objectMapper;

    public ChatController(PipelineExecutor pipelineExecutor, ChatTurnFinalizer finalizer,
                          ObjectMapper objectMapper) {
        this.pipelineExecutor = pipelineExecutor;
        this.finalizer = finalizer;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/chat")
    public UnifiedResponse chat(@RequestBody ChatRequest request) {
        return UnifiedResponse.success(run(request));
    }

    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> chatStream(@RequestBody ChatRequest request) {
        ChatResponse data = run(request);
        String json = objectMapper.writeValueAsString(data);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("data:" + json + "\n\n");
    }

    /** 跑流水线并把终端结果 + 会话标识收口为 {@link ChatResponse}。 */
    private ChatResponse run(ChatRequest request) {
        String sessionId = resolveSessionId(request);
        PipelineContext context = new PipelineContext(sessionId, request.message());
        PipelineResult result = pipelineExecutor.run(context);
        // Phase 13：终端后置钩子收口会话持久化 + 审计刷出（best-effort，不阻塞响应、停 MQ→主接口 200）
        finalizer.finalizeTurn(context, result);
        return new ChatResponse(sessionId, result.reply(), result.degraded(), result.scenario(),
                result.citations());
    }

    private String resolveSessionId(ChatRequest request) {
        return (request.sessionId() != null && !request.sessionId().isBlank())
                ? request.sessionId()
                : UUID.randomUUID().toString();
    }
}
