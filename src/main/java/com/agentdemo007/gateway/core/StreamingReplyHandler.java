package com.agentdemo007.gateway.core;

/**
 * 流式回复处理器（[[q2-token-streaming]]·引擎无关 seam，第六层）。
 *
 * <p>与 {@link ModelExecutor} 同居 core 包，<b>不依赖 LC4j 类型</b>——具体引擎（
 * {@link com.agentdemo007.gateway.llm.LangChain4jModelExecutor}）在 leaf 把本接口适配成
 * 引擎原生流式 handler（LC4j {@code StreamingChatResponseHandler}）。保持 {@code ModelExecutor}
 * 引擎无关边界（[[phase4-gateway-design]]：内核不渗引擎类型）。
 *
 * <p>调用契约：{@code onPartialResponse} 逐 token 多次 → {@code onCompleteResponse}（累积全文+token 数）
 * 恰一次；或 {@code onError} 恰一次（中途异常）。{@link com.agentdemo007.output.OutputStep} 流式分支
 * 在 {@code onPartialResponse} 经 {@code context.emitter()} 发 {@code TokenChunk}，{@code onCompleteResponse}
 * 写 {@code finalReply}；{@code onError}→回退阻塞 {@code chatRaw}（保完整主备容灾）。
 */
public interface StreamingReplyHandler {

    /** 部分回复（token 片段），可多次调用。 */
    void onPartialResponse(String token);

    /** 完整回复（累积全文）+ token 数，恰一次。 */
    void onCompleteResponse(String fullReply, int tokens);

    /** 中途异常，恰一次（调用方据此回退阻塞路径）。 */
    void onError(Throwable error);
}
