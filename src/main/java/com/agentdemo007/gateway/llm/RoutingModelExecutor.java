package com.agentdemo007.gateway.llm;

import com.agentdemo007.gateway.core.LlmRequest;
import com.agentdemo007.gateway.core.LlmResponse;
import com.agentdemo007.gateway.core.ModelExecutor;
import com.agentdemo007.gateway.exception.ModelSelectionException;

import java.util.Map;

/**
 * 多服务商路由执行器（主备容灾·合成 modelId→per-provider 翻译层）。
 *
 * <p>持有 {@code modelId → (该 provider 的 {@link LangChain4jModelExecutor}, raw 模型串)} 路由表。
 * {@link #execute} 把<b>合成 modelId</b>（如 {@code siliconflow-large}，路由/熔断用）翻成 provider 的
 * <b>raw 模型串</b>（如 {@code Qwen/Qwen3.5-35B-A3B}，发往 API 的 {@code model} 字段）再委托对应执行器。
 *
 * <p>合成 id 与 raw 串解耦：① 同一 raw 串跨 provider（如两家都托管 {@code gpt-4o}）也不在注册表冲突；
 * ② 路由/熔断/容灾全程用稳定合成 id，不渗入 raw 串。
 *
 * <p>引擎无关 seam：本类只做 modelId 翻译委托，不含 HTTP 逻辑——叶子 {@link ModelExecutor}（当前
 * {@link LangChain4jModelExecutor}，LC4j 实现）可整替而不改本类。未注册 id→{@link ModelSelectionException}（收口为模型选择错，不裸抛）。
 */
public class RoutingModelExecutor implements ModelExecutor {

    private final Map<String, Route> routes;

    public RoutingModelExecutor(Map<String, Route> routes) {
        this.routes = Map.copyOf(routes);
    }

    @Override
    public LlmResponse execute(LlmRequest request) {
        Route route = routes.get(request.modelId());
        if (route == null) {
            throw new ModelSelectionException("未注册的模型路由：modelId=" + request.modelId());
        }
        // 透传全部字段（messages/tools 用于工具路径；plain-chat 路径它们为空 List 不影响）——
        // 仅把合成 modelId 翻成 raw 模型串（发往 provider API 的 model 字段），其余原样委托。
        LlmResponse response = route.executor().execute(new LlmRequest(route.apiModel(), request.prompt(),
                request.maxTokens(), request.disableThinking(), request.messages(), request.tools()));
        // 合成 id 不渗入 raw 串（本类 javadoc 契约·"路由/熔断/容灾全程用稳定合成 id"）：
        // leaf（LangChain4jModelExecutor 等）按 raw 模型串执行后回传 raw modelId——此处回映射为请求的合成 id，
        // 使响应对外用稳定合成 id（调用方/审计/熔断 key 同构），不外泄 provider 的 raw 模型串。
        // toolCalls 一并透传（工具路径：模型发起的工具调用经路由层回传给 GatewayChatModel 翻译执行）。
        return new LlmResponse(request.modelId(), response.content(), response.tokens(), response.toolCalls());
    }

    /** 单条路由：委托执行器 + 发往 provider 的 raw 模型串。 */
    public record Route(ModelExecutor executor, String apiModel) {
    }
}
