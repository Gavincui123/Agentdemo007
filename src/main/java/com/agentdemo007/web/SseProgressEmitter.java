package com.agentdemo007.web;

import com.agentdemo007.common.progress.ProgressEmitter;
import com.agentdemo007.common.progress.ProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 进度桥接（#136 富事件 SSE 真流式·[[routeplan-design]]·Slice 4）。
 *
 * <p>把 {@link ProgressEvent} 翻成 SSE event：{@code event:<name>\ndata:<json>\n\n}。
 * {@code name} = variant→snake_case（step_started/step_finished/…随分类富化增补）；
 * {@code json} = 经注入 {@link ObjectMapper} 序列化 record（prod @Bean 带 {@code non_null}
 * 省 null scenario；序列化异常兜底 {@code {}}，不反噬）。工作线程推进流水线时调 {@link #emit}，
 * 每事件即 {@link SseEmitter#send} 立即 flush（真流式，非终端单 blob）；客户端断开
 * （{@link IOException}）→ 吞 + 记日志，不反噬流水线（进度 best-effort，收口优先）。
 *
 * <p>SSE 生命周期联动（超时治理）：持共享 {@code dead} 标志（{@link SseEmitter#onTimeout}/
 * {@code onError}/{@code onCompletion} 由 {@code ChatController} 置位）。置位后（连接超时/
 * 断开/已完成）{@link #emit} 静默 no-op——此前每个事件撞"already completed"各刷一条 WARN，
 * 超时后流水线继续推进时刷屏；现在只记 debug。
 *
 * <p>{@code eventName}/{@code jsonData} 为包级可见纯函数（供单测钉 name + 序列化）；
 * {@code emit} 委托 {@link SseEmitter#send}——真 SSE wire 在 Slice 5 ChatController 集成测覆盖。
 * 与 {@link com.agentdemo007.common.pipeline.StepOutcomeAuditor}（审计落库信道）正交：
 * 本桥只走 SSE 实时进度信道，两条信道同一 step 产出各自出口。
 */
public final class SseProgressEmitter implements ProgressEmitter {

    private static final Logger log = LoggerFactory.getLogger(SseProgressEmitter.class);

    private final SseEmitter sseEmitter;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean dead;

    public SseProgressEmitter(SseEmitter sseEmitter, ObjectMapper objectMapper) {
        this(sseEmitter, objectMapper, new AtomicBoolean(false));
    }

    /** 全参构造：{@code dead} 由 ChatController 持有并在 SSE 超时/断开/完成时置位。 */
    public SseProgressEmitter(SseEmitter sseEmitter, ObjectMapper objectMapper, AtomicBoolean dead) {
        this.sseEmitter = sseEmitter;
        this.objectMapper = objectMapper;
        this.dead = dead;
    }

    @Override
    public void emit(ProgressEvent event) {
        if (dead.get()) {
            // 连接已超时/断开/完成：静默丢弃（不再撞 already completed 刷 WARN）
            log.debug("SSE 已结束，进度事件丢弃：variant={}", event.getClass().getSimpleName());
            return;
        }
        try {
            sseEmitter.send(SseEmitter.event()
                    .name(eventName(event))
                    .data(jsonData(event), MediaType.TEXT_PLAIN));
        } catch (IOException e) {
            // 客户端断开/写失败：吞 + 自标关闭（后续事件静默，不再反复撞失败刷 WARN）
            dead.set(true);
            log.warn("SSE 进度事件发送失败，吞（不影响流水线）：{}", e.getMessage());
        } catch (RuntimeException e) {
            // SseEmitter 状态异常（已 complete 等）同样吞 + 自标关闭，防御
            dead.set(true);
            log.warn("SSE 进度事件发送异常，吞（不影响流水线）：{}", e.getMessage());
        }
    }

    @Override
    public boolean closed() {
        return dead.get();
    }

    /** 事件名（variant→snake_case）。包级可见供单测。 */
    String eventName(ProgressEvent event) {
        if (event instanceof ProgressEvent.StepStarted) {
            return "step_started";
        }
        if (event instanceof ProgressEvent.StepFinished) {
            return "step_finished";
        }
        if (event instanceof ProgressEvent.TokenChunk) {
            return "reply_chunk";
        }
        throw new IllegalStateException("未知 ProgressEvent 变体: " + event);
    }

    /** 事件 JSON 序列化（record→JSON；prod non_null 省 null；异常兜底 {}）。包级可见供单测。 */
    String jsonData(ProgressEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) { // Jackson 3 异常（checked/unchecked，[[routeplan-design]] RouteCandidateParser 同模式）
            log.warn("进度事件序列化失败，兜底空 JSON：{}", e.getMessage());
            return "{}";
        }
    }
}
