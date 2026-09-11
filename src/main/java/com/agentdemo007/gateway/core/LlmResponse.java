package com.agentdemo007.gateway.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * LLM 执行响应（引擎无关 DTO，{@link ModelExecutor} 的产出）。
 *
 * @param modelId 实际产出本响应的模型（可能是备选模型；路由层回映射为合成 id，不渗 raw 串）
 * @param content 文本回复（模型发起工具调用时可为空——看 {@link #toolCalls}）
 * @param tokens  本次用量（供 {@link TokenBudgetChecker} 记账）
 * @param toolCalls 模型发起的工具调用（② 工具调用扩展；空=纯文本回复，非空=模型要调工具，
 *                  由 {@code GatewayChatModel} 翻译回 LC4j {@code AiMessage.toolExecutionRequests()}
 *                  供 AiServices 循环执行）
 */
public record LlmResponse(String modelId, String content, int tokens, List<ToolExecutionRequest> toolCalls) {

    /** 兼容构造：纯文本回复（toolCalls 空）。 */
    public LlmResponse(String modelId, String content, int tokens) {
        this(modelId, content, tokens, List.of());
    }
}
