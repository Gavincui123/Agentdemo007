package com.agentdemo007.common.progress;

import com.agentdemo007.common.degradation.DegradationScenario;

/**
 * 进度事件分类（#136 富事件 SSE 真流式·[[routeplan-design]]）。
 *
 * <p>密封分类：每步起止 + 语义节点 + 终端。事件经 {@link ProgressEmitter} 流出，由
 * {@code SseProgressEmitter} 桥接到 {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter}
 * 真异步 flush（SSE event {@code name} + JSON {@code data}，每事件 flush、非终端单 blob）。
 *
 * <p>{@link Outcome} 标注步骤产出形状，与 {@link com.agentdemo007.common.pipeline.StepOutcome}
 * 四态（Proceed/ShortCircuit/Degrade/Retry）+ 异常收口（EXCEPTION）对齐——同一语义两视角：
 * StepOutcome 是步骤内出口契约，Outcome 是对观察者外发的进度标签。
 *
 * <p>分类富化随 wiring slice 增补 sealed permits：本 slice 只起 StepStarted/StepFinished
 * （驱动编排器 per-step 发射）；RouteDecided/ToolCalled/RagRetrieved/ReplyReady 随各自步骤
 * wiring 时增补（sealed permits 增项即加，不破既有）。
 */
public sealed interface ProgressEvent permits ProgressEvent.StepStarted, ProgressEvent.StepFinished, ProgressEvent.TokenChunk {

    /** 步骤产出形状（与 StepOutcome 四态 + EXCEPTION 对齐；观察者视角的进度标签）。 */
    enum Outcome { PROCEED, SHORT_CIRCUIT, DEGRADE, RETRY, EXCEPTION }

    /** 步骤开始：{@code step}=步骤名（PipelineStep.name）。 */
    record StepStarted(String step) implements ProgressEvent {
    }

    /**
     * 步骤结束：{@code outcome} 标产出形状；{@code scenario} 仅在 SHORT_CIRCUIT/DEGRADE 时非空
     * （携带降级场景，供前端区分话术/审计），PROCEED/RETRY/EXCEPTION 可空。
     */
    record StepFinished(String step, Outcome outcome, DegradationScenario scenario) implements ProgressEvent {
    }

    /**
     * 逐 token 流式（[[q2-token-streaming]]）：大模型生成的部分文本块，经 {@code SseProgressEmitter}
     * 翻成 {@code reply_chunk} SSE 事件实时 flush（best-effort，{@link java.io.IOException} 吞不反噬流水线）。
     * 由 {@code OutputStep} 流式分支在每个 {@code onPartialResponse} 回调发射；终端 {@code reply_ready}
     * 仍带完整组装回复（出口形状不变，前端兼容）。
     */
    record TokenChunk(String text) implements ProgressEvent {
    }
}
