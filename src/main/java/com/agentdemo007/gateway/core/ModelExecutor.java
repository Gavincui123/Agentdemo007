package com.agentdemo007.gateway.core;

/**
 * 模型执行器边界（第六层·LLM 引擎适配口）。
 *
 * <p>实现把 {@link LlmRequest} 翻译成具体引擎（LangChain4j {@code ChatModel} 等）调用并产出
 * {@link LlmResponse}。执行失败抛任意 {@link RuntimeException}，由 {@link FailoverExecutor} 决定重试/转移。
 * 生产实现经 {@code ResilientExecutor}（Phase 5）包裹重试退避；本接口本身只表达"执行一次"。
 */
public interface ModelExecutor {

    LlmResponse execute(LlmRequest request);

    /**
     * 流式执行（[[q2-token-streaming]]）：逐 token 经 {@link StreamingReplyHandler} 回调，非阻塞返回整段。
     * 默认不支持（抛 {@link UnsupportedOperationException}）；流式 capable 执行器（如 LC4j leaf）覆写。
     * 流式不做中途故障转移（主模型 only）；调用方（OutputStep）{@code onError}→回退阻塞 {@code execute}
     * （有完整主备容灾），韧性不丢。
     */
    default void stream(LlmRequest request, StreamingReplyHandler handler) {
        throw new UnsupportedOperationException("此 ModelExecutor 不支持流式（streaming）");
    }
}
