package com.agentdemo007.gateway.core;

import com.agentdemo007.gateway.config.FlowControlPolicy;

/**
 * 统一模型网关（第六层·所有出站 LLM 调用的唯一入口）。
 *
 * <p>固定三段流：① {@link TokenBudgetChecker#check} 预算关卡（超限→话术短路，零 LLM）；
 * ② {@link FailoverExecutor#execute} 主模型+备选执行（全失败→{@code LlmUnavailableException}）；
 * ③ {@link TokenBudgetChecker#record} 记账。
 * 收口：任何 LLM 调用只经此网关，不在各业务层散落直连引擎。
 */
public class UnifiedModelGateway {

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

    public TokenBudgetChecker budget() {
        return budgetChecker;
    }
}
