package com.agentdemo007.common.progress;

/**
 * 进度发射 seam（#136 富事件 SSE 真流式·[[routeplan-design]]）。
 *
 * <p>注入 {@link com.agentdemo007.common.pipeline.PipelineContext}（每请求实例，非 per-step），
 * 编排器/各步产 {@link ProgressEvent} 经此发射。{@link #NO_OP} 为同步 {@code /chat} + 单测默认
 * （不外泄、零开销，emit 丢弃）；{@code SseProgressEmitter}（后续 slice）桥接到
 * {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter} 真异步 flush
 * （工作线程跑流水线，每事件 {@code send} 立即 flush，终端 {@code complete}）。
 *
 * <p>与既有收口正交：{@link com.agentdemo007.common.pipeline.StepOutcomeAuditor} 收口**审计**
 * （落库、§5.14），ProgressEmitter 收口**对用户实时进度**（SSE 流出）——两条信道，同一 step 产出
 * 各自出口，互不替代。{@link com.agentdemo007.common.pipeline.PipelineExecutor#run} 签名不变
 * （emitter 经 context 携带，非参注入），linear/graph 两编排器同读 {@code context.emitter()}。
 *
 * <p>线程安全由实现保证：NO_OP 无状态；SseProgressEmitter 委托 {@code SseEmitter}（Spring 保证
 * send 线程安全）。
 */
public interface ProgressEmitter {

    /** 发射一个进度事件。 */
    void emit(ProgressEvent event);

    /**
     * 信道是否已关闭（SSE 超时/客户端断开/已完成）。默认 false（NO_OP/同步路径恒开）。
     * 编排器在每个步骤边界轮询：已关闭 → 协作式取消，不再执行后续步骤（省 LLM 调用与配额，
     * 用户"停止对话"即真停止——进行中的单步调用量子自然结束，跨步即断）。
     */
    default boolean closed() {
        return false;
    }

    /** 空实现：同步 /chat + 单测默认（丢事件、零开销、不外泄）。 */
    ProgressEmitter NO_OP = event -> { };
}
