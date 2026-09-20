package com.agentdemo007.gateway.core;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 统一模型网关请求（由上层"模型网关步骤"从 {@code ModelConfigCenter}+{@code ModelSelector} 装配）。
 *
 * <p>携带主模型、提示、预算、容灾与流控策略。网关据此做：预算关卡 → 故障转移执行 → 记账。
 * 收口：网关不直接读配置中心，所有策略随请求注入，便于测试与隔离。
 *
 * <p>{@code disableThinking}（每请求）：意图驱动关思考的载波——由 {@code ChatLlmService.invoke}
 * 按意图算定后注入，经 {@link com.agentdemo007.gateway.core.FailoverExecutor} 透传到 {@link LlmRequest}。
 *
 * <p>{@code messages} + {@code tools}（② 工具调用扩展）：plain-chat 路径只用 {@code prompt}（单串，由
 * {@code ChatLlmService} 装配，messages/tools 空）；工具调用路径经 {@code GatewayChatModel} 把 LC4j
 * {@code ChatRequest}（多消息含 tool-result 回传 + tool-spec）翻译成本请求——{@code messages} 承多轮消息、
 * {@code tools} 承工具规格，{@code prompt} 置空。{@link FailoverExecutor} 把它们原样透传进 {@link LlmRequest}，
 * 由执行器据此建 LC4j {@code ChatRequest}：有 messages 用之（工具路径），否则用 prompt（plain-chat）。
 *
 * <p>{@code scene}（场景标签，可空）：出站调用的业务用途（查询改写/意图识别/路由计划/会话摘要/
 * 回答生成/工具调用）——由 {@code ChatLlmService} 各入口与 {@code GatewayChatModel} 标注，
 * {@link FailoverExecutor} 的「LLM出站」日志据此可区分同轮多次调用的归属（此前 4 行小模型出站
 * 日志同形不可辨，只能靠下游步骤日志倒推）。缺省经 {@link #sceneOrDefault()} 落「未标注」。
 *
 * <p>与 {@link LlmRequest} 同构（DTO 直接持 LC4j 类型——LC4j 是唯一引擎，引擎无关性由接口边界表达）。
 */
public record GatewayRequest(String primaryModelId, String prompt, int maxTokens,
                             FailoverPolicy failoverPolicy, FlowControlPolicy flowControlPolicy,
                             boolean disableThinking,
                             List<ChatMessage> messages, List<ToolSpecification> tools,
                             String scene) {

    /** 场景标签落日志口径：空/缺省 → 「未标注」（兼容未带标签的调用方）。 */
    public String sceneOrDefault() {
        return (scene == null || scene.isBlank()) ? "未标注" : scene;
    }

    /** 兼容构造（场景缺省）：工具调用路径，messages + tools（prompt 置空——工具路径以 messages 为准）。 */
    public GatewayRequest(String primaryModelId, List<ChatMessage> messages, List<ToolSpecification> tools,
                         int maxTokens, FailoverPolicy failoverPolicy, FlowControlPolicy flowControlPolicy,
                         boolean disableThinking) {
        this(primaryModelId, messages, tools, maxTokens, failoverPolicy, flowControlPolicy, disableThinking, null);
    }

    /** 工具调用路径构造：messages + tools（prompt 置空——工具路径以 messages 为准）+ 场景标签。 */
    public GatewayRequest(String primaryModelId, List<ChatMessage> messages, List<ToolSpecification> tools,
                         int maxTokens, FailoverPolicy failoverPolicy, FlowControlPolicy flowControlPolicy,
                         boolean disableThinking, String scene) {
        this(primaryModelId, null, maxTokens, failoverPolicy, flowControlPolicy, disableThinking,
                messages == null ? List.of() : messages,
                tools == null ? List.of() : tools, scene);
    }

    /** 兼容构造（场景缺省）：不关思考（disableThinking=false），供未涉及思考控制的调用方/测试。 */
    public GatewayRequest(String primaryModelId, String prompt, int maxTokens,
                         FailoverPolicy failoverPolicy, FlowControlPolicy flowControlPolicy) {
        this(primaryModelId, prompt, maxTokens, failoverPolicy, flowControlPolicy, false, null);
    }

    /** 兼容构造：plain-chat（messages/tools 空），disableThinking 显式，场景缺省。 */
    public GatewayRequest(String primaryModelId, String prompt, int maxTokens,
                         FailoverPolicy failoverPolicy, FlowControlPolicy flowControlPolicy,
                         boolean disableThinking) {
        this(primaryModelId, prompt, maxTokens, failoverPolicy, flowControlPolicy, disableThinking,
                List.of(), List.of(), null);
    }

    /** plain-chat（messages/tools 空）+ 场景标签（ChatLlmService 各入口用）。 */
    public GatewayRequest(String primaryModelId, String prompt, int maxTokens,
                         FailoverPolicy failoverPolicy, FlowControlPolicy flowControlPolicy,
                         boolean disableThinking, String scene) {
        this(primaryModelId, prompt, maxTokens, failoverPolicy, flowControlPolicy, disableThinking,
                List.of(), List.of(), scene);
    }
}
