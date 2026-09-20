package com.agentdemo007.capability.tool;

import com.agentdemo007.gateway.config.FailoverPolicy;
import com.agentdemo007.gateway.config.FlowControlPolicy;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.gateway.core.UnifiedModelGateway;
import com.agentdemo007.gateway.llm.GatewayChatModel;
import com.agentdemo007.intent.Intent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * LC4j 工具调用执行器（② Slice 3 option A 数据步 → <b>2026-09-17 推翻「单次前向」：有界 Agent loop</b>）。
 *
 * <p>按 CHIT_CHAT 路由（小模型 + 关思考，工具探测=决策调用，镜像
 * {@link com.agentdemo007.gateway.llm.ChatLlmService#decide}）建 {@link GatewayChatModel}，驱动
 * <b>至多 {@code app.tool.max-iterations} 轮</b>的工具调用循环（DAG 业务每步兜底，收口最重要）：
 * <ol>
 *   <li>每轮 {@code doChat(messages + tools)}：模型出 {@code tool_calls} → 逐个经
 *       {@link ResilientToolExecutor#invoke} 执行（内部已含分诊重试/超时/熔断/错误收口）；</li>
 *   <li><b>失败回喂</b>：本轮任一失败（结构化错误 JSON，{@link ToolError#toText}）→
 *       {@code AiMessage(tool_calls)} + {@link ToolExecutionResultMessage}（成功失败都回传，
 *       保持 tool_call_id 配对）追加进 messages → 下一轮模型自纠正（改参重调/换工具）或转文本澄清；</li>
 *   <li><b>成功即停</b>：本轮全成功 → 返回累计结果（数据步收口，不空转烧轮次）；</li>
 *   <li><b>澄清出口</b>：第 2 轮起模型转纯文本回复（放弃自纠正的澄清/策略话术）→
 *       {@link ToolTurn#loopReply()} 交终答 LLM 整合（异常信息交 LLM 做策略/回复客户，系统不吞）；</li>
 *   <li><b>轮次耗尽</b>：模型仍出 tool_calls → 停止循环，累计结果（含失败）交终答 LLM 如实说明。</li>
 * </ol>
 * 首轮无 {@code tool_calls} → 空转 {@link ToolTurn#empty()}（正常对话，不触发工具，省一次探测成本）。
 *
 * <p>退役映射：旧手撸 Detector/ParamParser/SchemaValidator/Reparser 循环 + 旧 {@code app.tool.max-iterations}
 * 自纠正计数在 ② Slice 3 随手撸路径退役（当时改单次前向）；本轮按用户裁决推翻单次前向、复活轮次护栏
 * （新语义：Agent loop 轮次上限，1=退化为单次前向）。schema 由 {@code @Tool} 注解经
 * {@link dev.langchain4j.agent.tool.ToolSpecifications#toolSpecificationFrom} 生成，参数强转+反射调归
 * LC4j 原生（[[dont-hardwrite-use-dep-methods]]：库方法优先）。
 */
public class ToolCallExecutor {

    private final UnifiedModelGateway gateway;
    private final ModelConfigCenter center;
    private final int maxTokens;
    private final List<ToolSpecification> specs;
    private final Map<String, ResilientToolExecutor> executors;
    private final Map<String, ToolCategory> categoryMap;
    private final int maxRounds;

    public ToolCallExecutor(UnifiedModelGateway gateway, ModelConfigCenter center, int maxTokens,
                            List<ToolSpecification> specs, Map<String, ResilientToolExecutor> executors,
                            Map<String, ToolCategory> categoryMap, int maxRounds) {
        this.gateway = gateway;
        this.center = center;
        this.maxTokens = maxTokens;
        this.specs = specs;
        this.executors = (executors != null) ? executors : Map.of();
        this.categoryMap = (categoryMap != null) ? categoryMap : Map.of();
        this.maxRounds = Math.max(maxRounds, 1);
    }

    /** 兼容构造（测试/scripted 覆写场景）：默认 2 轮（首次 + 1 次错误自纠正）。 */
    public ToolCallExecutor(UnifiedModelGateway gateway, ModelConfigCenter center, int maxTokens,
                            List<ToolSpecification> specs, Map<String, ResilientToolExecutor> executors,
                            Map<String, ToolCategory> categoryMap) {
        this(gateway, center, maxTokens, specs, executors, categoryMap, 2);
    }

    /**
     * 执行工具调用环节（有界 Agent loop）：探测 → 执行 → 失败回喂自纠正/澄清 → 结构化结果。
     *
     * @param query 用户输入/标准化 Query
     * @return {@link ToolTurn}（累计结果 + 模型澄清话术槽）；模型未出 tool_calls 返回空
     * @throws com.agentdemo007.resilience.ToolCircuitOpenException breaker OPEN（交上层收口 TOOL_FAILURE）
     */
    public ToolTurn execute(String query) {
        GatewayChatModel chatModel = buildChatModel();
        if (chatModel == null) {
            // 无 CHIT_CHAT 路由（dev/未装配/llm.enabled=false）→ 不发起工具探测，交下游正常对话
            // （每步降级：工具探测缺路由不抛、不阻塞主链路，[[degradation-and-eval-principles]]）
            return ToolTurn.empty();
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new UserMessage(query));
        List<ToolCallResult> results = new ArrayList<>();
        for (int round = 1; round <= maxRounds; round++) {
            ChatRequest req = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(specs)
                    .build();
            ChatResponse resp = chatModel.doChat(req);
            List<ToolExecutionRequest> calls = resp.aiMessage().toolExecutionRequests();
            if (calls == null || calls.isEmpty()) {
                if (round == 1) {
                    return ToolTurn.empty(); // 正常对话（不触发工具）
                }
                // 工具环节后模型转文本：放弃自纠正 → 澄清/策略话术（交终答 LLM 整合，非系统吞异常）
                return new ToolTurn(List.copyOf(results), blankToNull(resp.aiMessage().text()));
            }
            // LC4j 消息配对约定：先 AiMessage(tool_calls)，后逐调用 ToolExecutionResultMessage
            messages.add(resp.aiMessage());
            boolean anyError = false;
            for (ToolExecutionRequest call : calls) {
                ResilientToolExecutor exec = executors.get(call.name());
                ToolCallResult r = (exec != null)
                        ? toResult(call, exec.invoke(call))
                        : unknownToolResult(call); // 模型幻觉工具名：错误结果回喂（跳过会破坏 tool_call_id 配对）
                results.add(r);
                anyError |= r.isError();
                messages.add(ToolExecutionResultMessage.from(resultId(call), call.name(), r.content()));
            }
            if (!anyError) {
                return new ToolTurn(List.copyOf(results), null); // 全成功即停（数据步收口，省轮次）
            }
            // 有失败：错误结果已回喂 → 下一轮模型自纠正（改参重调/换工具）或转澄清文本
        }
        return new ToolTurn(List.copyOf(results), null); // 轮次耗尽：失败结果交终答 LLM 如实向客户说明
    }

    private ToolCallResult toResult(ToolExecutionRequest call, ToolInvocation inv) {
        ToolCategory category = categoryMap.getOrDefault(call.name(), ToolCategory.COMPUTE);
        return new ToolCallResult(call.name(), inv.content(), category, inv.error());
    }

    private static ToolCallResult unknownToolResult(ToolExecutionRequest call) {
        ToolError error = new ToolError(ToolErrorKind.UNKNOWN_TOOL, "工具不存在：" + call.name(), 0);
        return new ToolCallResult(call.name(), error.toText(call.name()), ToolCategory.COMPUTE, error);
    }

    /** tool_call_id 配对：模型未给 id（部分模型/scripted 场景）时生成占位，保证回传消息可配对。 */
    private static String resultId(ToolExecutionRequest call) {
        return (call.id() == null || call.id().isBlank()) ? UUID.randomUUID().toString() : call.id();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 按 CHIT_CHAT 路由（小模型 + 关思考）解析并建 {@link GatewayChatModel}。工具探测=决策调用，
     * 镜像 {@link com.agentdemo007.gateway.llm.ChatLlmService#decide}（恒关思考 + 闲聊通道小模型）。
     *
     * <p>protected 供测试覆写为 scripted（证 loop 逻辑，免 ModelConfigCenter 装配耦合）。
     * 路由解析 per-call（非 bean 构建期）——避开 ModelConfigCenter 启动加载时序（快照 refresh 后才可读），
     * 与 {@link com.agentdemo007.gateway.llm.ChatLlmService} 同样 per-call 取 routeFor/failover/flowControl。
     */
    protected GatewayChatModel buildChatModel() {
        if (center == null) {
            return null;
        }
        Optional<RouteRule> rule = center.routeFor(Intent.CHIT_CHAT);
        if (rule.isEmpty()
                || rule.get().targetModelId() == null || rule.get().targetModelId().isBlank()) {
            // dev/未装配：devSnapshot 无 CHIT_CHAT 路由规则 → 无工具探测小模型 → 返回 null（execute 降级返空）
            return null;
        }
        String primary = rule.get().targetModelId();
        // 主备容灾：按 CHIT_CHAT 路由备链建 FailoverPolicy（maxRetries=备链长度），镜像 ChatLlmService.invoke
        RouteRule r = rule.get();
        FailoverPolicy failover = FailoverPolicy.builder(r.id())
                .fallbackModelIds(r.fallbackModelIds())
                .maxRetries(r.fallbackModelIds().size())
                .build();
        FlowControlPolicy flow = center.flowControl();
        return new GatewayChatModel(gateway, primary, maxTokens, failover, flow, true); // disableThinking=true（决策调用）
    }
}
