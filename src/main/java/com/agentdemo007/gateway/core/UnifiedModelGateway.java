package com.agentdemo007.gateway.core;

import com.agentdemo007.gateway.config.FlowControlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一模型网关（第六层·所有出站 LLM 调用的唯一入口）。
 *
 * <p>固定三段流：① {@link TokenBudgetChecker#check} 预算关卡（超限→话术短路，零 LLM）；
 * ② {@link FailoverExecutor#execute} 主模型+备选执行（全失败→{@code LlmUnavailableException}）；
 * ③ {@link TokenBudgetChecker#record} 记账。
 * 收口：任何 LLM 调用只经此网关，不在各业务层散落直连引擎。
 */
public class UnifiedModelGateway {

    private static final Logger log = LoggerFactory.getLogger(UnifiedModelGateway.class);

    private final ModelExecutor executor;
    private final TokenBudgetChecker budgetChecker;
    private final FailoverExecutor failoverExecutor;

    public UnifiedModelGateway(ModelExecutor executor, TokenBudgetChecker budgetChecker,
                               FailoverExecutor failoverExecutor) {
        this.executor = executor;
        this.budgetChecker = budgetChecker;
        this.failoverExecutor = failoverExecutor;
    }

    public LlmResponse invoke(GatewayRequest request) {
        FlowControlPolicy policy = request.flowControlPolicy();
        budgetChecker.check(request, policy); // 超限 → RateLimitExceededException
        LlmResponse response = failoverExecutor.execute(request, executor); // 全失败 → LlmUnavailableException
        budgetChecker.record(response.tokens(), policy);
        return response;
    }

    /**
     * 流式出站（[[q2-token-streaming]]）：预算关卡仍守，主模型直接 {@code executor.stream}——
     * <b>无中途故障转移</b>（流式不可中途切备/重试）。同步抛（预算超限/模型选择错/leaf 前置抛）向上传，
     * 由 {@code ChatLlmService.chatRawStream} 捕获转 {@code handler.onError}，调用方（OutputStep）回退阻塞
     * {@code invoke}（有完整主备容灾）→韧性不丢。
     */
    public void stream(GatewayRequest request, StreamingReplyHandler handler) {
        FlowControlPolicy policy = request.flowControlPolicy();
        budgetChecker.check(request, policy); // 超限 → RateLimitExceededException（向上传→onError）
        // 流式出站此前零日志（同步路径有 FailoverExecutor 的「LLM出站」行，流式不经它）——补一行出站登记，
        // scene+model 与同步口径对齐（durMs 归执行器回调口径，入口行不打）；回答生成走流式的可见性由此保证
        log.info("LLM出站(流式) scene={} model={}", request.sceneOrDefault(), request.primaryModelId());
        executor.stream(new LlmRequest(request.primaryModelId(), request.prompt(), request.maxTokens(),
                request.disableThinking(), request.messages(), request.tools()), handler);
    }

    public TokenBudgetChecker budget() {
        return budgetChecker;
    }
}
