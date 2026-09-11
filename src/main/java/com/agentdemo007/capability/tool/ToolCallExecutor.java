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
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LC4j 工具调用执行器（② Slice 3·option A 数据步·替手撸 {@code ToolExecutor} 角色）。
 *
 * <p>单次前向：按 CHIT_CHAT 路由（小模型 + 关思考，工具探测=决策调用，镜像
 * {@link com.agentdemo007.gateway.llm.ChatLlmService#decide}）建 {@link GatewayChatModel} →
 * {@code doChat(messages=[query], tools=specs)} → 模型出 {@code tool_calls} 则经
 * {@link ResilientToolExecutor}（包 {@link dev.langchain4j.service.tool.DefaultToolExecutor}）执行真 {@code @Tool}
 * → 返回结果列表（入 {@code context.toolResults}，客观数据层消费）；无 {@code tool_calls} → 空（正常对话，不触发工具）。
 * breaker OPEN → 透传 {@link com.agentdemo007.resilience.ToolCircuitOpenException}，交 {@code ToolExecutionStep}
 * 收口 {@code ShortCircuit(TOOL_FAILURE)}（零 LLM 话术短路，①话术短路 + ②每步降级）。
 *
 * <p>退役手撸 Detector/ParamParser/SchemaValidator/Reparser：模型直接出结构化 {@code tool_calls}（含 JSON 参数），
 * 无关键词检测/手解析/手校验/重解析循环——schema 由 {@code @Tool} 注解经
 * {@link dev.langchain4j.agent.tool.ToolSpecifications#toolSpecificationFrom} 生成，参数强转+反射调归
 * {@link dev.langchain4j.service.tool.DefaultToolExecutor}（[[dont-hardwrite-use-dep-methods]]：库方法优先；
 * [[langchain4j-boot4-compat-findings]]：seam=委托非重写）。
 *
 * <p>不驱动循环（区别 AiServices 自驱动 agent 模式）——单次前向出结果作数据，下游 ContextBuilder+终答步不动
 * （pipeline 尾零改，[[degradation-and-eval-principles]]：收口最重要）。
 */
public class ToolCallExecutor {

    private final UnifiedModelGateway gateway;
    private final ModelConfigCenter center;
    private final int maxTokens;
    private final List<ToolSpecification> specs;
    private final Map<String, ToolExecutor> executors;

    public ToolCallExecutor(UnifiedModelGateway gateway, ModelConfigCenter center, int maxTokens,
                            List<ToolSpecification> specs, Map<String, ToolExecutor> executors) {
        this.gateway = gateway;
        this.center = center;
        this.maxTokens = maxTokens;
        this.specs = specs;
        this.executors = executors;
    }

    /**
     * 执行工具调用（若模型判定需要）：单次前向 doChat → dispatch tool_calls。
     *
     * @param query 用户输入/标准化 Query
     * @return 工具结果列表；模型未出 tool_calls 返回空
     * @throws com.agentdemo007.resilience.ToolCircuitOpenException breaker OPEN（交上层收口 TOOL_FAILURE）
     */
    public List<String> execute(String query) {
        GatewayChatModel chatModel = buildChatModel();
        if (chatModel == null) {
            // 无 CHIT_CHAT 路由（dev/未装配/llm.enabled=false）→ 不发起工具探测，交下游正常对话
            // （每步降级：工具探测缺路由不抛、不阻塞主链路，[[degradation-and-eval-principles]]）
            return List.of();
        }
        ChatRequest req = ChatRequest.builder()
                .messages(new UserMessage(query))
                .toolSpecifications(specs)
                .build();
        ChatResponse resp = chatModel.doChat(req);
        List<ToolExecutionRequest> calls = resp.aiMessage().toolExecutionRequests();
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        for (ToolExecutionRequest call : calls) {
            ToolExecutor exec = executors.get(call.name());
            if (exec == null) {
                continue; // 模型幻觉工具名：跳过（降级—无结果，下游正常对话）
            }
            results.add(exec.execute(call, null)); // ResilientToolExecutor → DefaultToolExecutor 算真 @Tool
        }
        return results;
    }

    /**
     * 按 CHIT_CHAT 路由（小模型 + 关思考）解析并建 {@link GatewayChatModel}。工具探测=决策调用，
     * 镜像 {@link com.agentdemo007.gateway.llm.ChatLlmService#decide}（恒关思考 + 闲聊通道小模型）。
     *
     * <p>protected 供测试覆写为 scripted（证 dispatch 逻辑，免 ModelConfigCenter 装配耦合）。
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
