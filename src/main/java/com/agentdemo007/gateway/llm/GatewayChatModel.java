package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import com.agentdemo007.gateway.core.GatewayRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

/**
 * 网关适配 {@link ChatModel}（② 工具调用扩展）：把 LC4j {@link ChatRequest}（由 {@code AiServices}
 * function-calling 循环每轮下发，载多轮 messages + tool-spec）翻译成 {@link GatewayRequest} 工具路径，
 * 经 {@link UnifiedModelGateway} 出站（复用预算关卡/主备故障转移/模型级熔断/关思考——① ① 全栈韧性），
 * 再把 {@link LlmResponse}（含 {@code toolCalls}）翻成 LC4j {@link ChatResponse} 交回 {@code AiServices} 继续循环。
 *
 * <p>引擎无关 seam 的回报：LC4j {@code AiServices} 当家循环（派发/参数解析/自纠正/回传全归 LC4j 原生），
 * 仅把"调模型"这一步经本类收口进网关——韧性（重试/故障转移/熔断/关思考/预算）不二选一，全复用既有栈。
 * 关联 [[langchain4j-boot4-compat-findings]] [[phase4-gateway-design]] [[phase-llm-primary-backup-breaker]]。
 *
 * <p>配置态（构造期固定）：{@code modelId}/{@code maxTokens}/{@code failover}/{@code flow}/{@code disableThinking}——
 * 每轮 {@code AiServices} 只变 messages/tools（来自 {@link ChatRequest}），其余由本类持有（意图驱动关思考：
 * 工具循环属非闲聊，disableThinking 由调用方按 {@code llm.thinking.enabled} 算定注入）。
 */
public class GatewayChatModel implements ChatModel {

    private final UnifiedModelGateway gateway;
    private final String modelId;
    private final int maxTokens;
    private final FailoverPolicy failoverPolicy;
    private final FlowControlPolicy flowControlPolicy;
    private final boolean disableThinking;

    public GatewayChatModel(UnifiedModelGateway gateway, String modelId, int maxTokens,
                            FailoverPolicy failoverPolicy, FlowControlPolicy flowControlPolicy,
                            boolean disableThinking) {
        this.gateway = gateway;
        this.modelId = modelId;
        this.maxTokens = maxTokens;
        this.failoverPolicy = failoverPolicy;
        this.flowControlPolicy = flowControlPolicy;
        this.disableThinking = disableThinking;
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        // IN 翻译：ChatRequest 的 messages（多轮含 tool-result 回传）+ tool-spec → GatewayRequest 工具路径
        // （prompt 置空——工具路径以 messages 为准；其余配置态由本类持有）。
        List<ChatMessage> messages = chatRequest.messages();
        List<ToolSpecification> tools = chatRequest.toolSpecifications();
        GatewayRequest req = new GatewayRequest(modelId, messages, tools,
                maxTokens, failoverPolicy, flowControlPolicy, disableThinking);
        LlmResponse resp = gateway.invoke(req); // 预算关卡 → failover/熔断/关思考 → 执行器 → 记账

        // OUT 翻译：LlmResponse → ChatResponse（交回 AiServices 继续循环）
        boolean hasToolCalls = resp.toolCalls() != null && !resp.toolCalls().isEmpty();
        AiMessage.Builder aiB = AiMessage.builder();
        FinishReason finishReason;
        if (hasToolCalls) {
            aiB.toolExecutionRequests(resp.toolCalls()); // tool_calls 回交 AiServices 执行
            if (resp.content() != null && !resp.content().isBlank()) {
                aiB.text(resp.content()); // content 可与 tool_calls 并存
            }
            finishReason = FinishReason.TOOL_EXECUTION;
        } else {
            aiB.text(resp.content()); // 纯文本终答
            finishReason = FinishReason.STOP;
        }
        return ChatResponse.builder()
                .aiMessage(aiB.build())
                .finishReason(finishReason)
                .tokenUsage(new TokenUsage(resp.tokens()))
                .build();
    }
}
