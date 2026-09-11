package com.agentdemo007.gateway.core;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * LLM 执行请求（引擎无关 DTO，{@link ModelExecutor} 的入参）。
 *
 * <p>收口：网关与执行器之间只传此强类型。① {@code LangChain4jModelExecutor} 已把它翻译成
 * {@code OpenAiChatModel.chat} 调用。
 *
 * <p>{@code disableThinking}（每请求）：意图驱动关思考——闲聊(CHIT_CHAT)恒 true（关思考），
 * 其他意图由 {@code llm.thinking.enabled} 开关定。经 {@code GatewayRequest}→{@code FailoverExecutor}
 * →{@code RoutingModelExecutor} 透传到执行器，据此合并 provider 各自关思考参数（SF/Aliyun enable_thinking）。
 *
 * <p>{@code messages} + {@code tools}（② 工具调用扩展）：plain-chat 路径只用 {@code prompt}（单串）；
 * 工具调用路径经 {@code GatewayChatModel} 把 LC4j {@code ChatRequest}（多消息含 tool-result 回传 + tool-spec）
 * 翻译成本 DTO——{@code messages} 承多轮消息，{@code tools} 承工具规格。执行器据此建 {@code ChatRequest}：
 * 有 messages 用之（工具路径），否则用 prompt（plain-chat）。
 *
 * <p>engine-agnostic 内核已采 LC4j 为唯一引擎（① 退役 OpenAiModelExecutor-HTTP），故 DTO 直接持 LC4j 类型
 * （{@code ChatMessage}/{@code ToolSpecification}）——引擎无关性现由 {@link ModelExecutor} 接口边界表达，
 * 非 DTO 内核去 LC4j 依赖。关联 [[langchain4j-boot4-compat-findings]] [[phase4-gateway-design]]。
 */
public record LlmRequest(String modelId, String prompt, int maxTokens, boolean disableThinking,
                         List<ChatMessage> messages, List<ToolSpecification> tools) {

    /** 工具调用路径构造：messages + tools（prompt 置空——工具路径以 messages 为准）。 */
    public LlmRequest(String modelId, List<ChatMessage> messages, List<ToolSpecification> tools,
                      int maxTokens, boolean disableThinking) {
        this(modelId, null, maxTokens, disableThinking,
                messages == null ? List.of() : messages,
                tools == null ? List.of() : tools);
    }

    /** 兼容构造：plain-chat（messages/tools 空），disableThinking 显式。 */
    public LlmRequest(String modelId, String prompt, int maxTokens, boolean disableThinking) {
        this(modelId, prompt, maxTokens, disableThinking, List.of(), List.of());
    }

    /** 兼容构造：不关思考 + plain-chat。 */
    public LlmRequest(String modelId, String prompt, int maxTokens) {
        this(modelId, prompt, maxTokens, false);
    }
}
