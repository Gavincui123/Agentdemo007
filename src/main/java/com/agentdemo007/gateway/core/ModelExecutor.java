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
}
