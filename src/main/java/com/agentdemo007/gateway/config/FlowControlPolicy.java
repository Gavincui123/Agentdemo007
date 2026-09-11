package com.agentdemo007.gateway.config;

import java.time.Duration;

/**
 * 模型网关流控策略（RPM / Token 日配额上限）。
 *
 * <p>由 {@code TokenBudgetChecker}（网关预算关卡）消费：
 * 超速率（RPM）或超日 Token 配额 → 收口为 RATE_LIMITED 话术，不调用模型（零 LLM）。
 *
 * <p>说明：并发上限（maxConcurrent）经 code-review 移除——计划 §4 流控策略为 RPM/TPM/成本上限，
 * 未要求并发维度，且 {@code TokenBudgetChecker} 无"请求完成释放"的成对生命周期来维护并发计数，
 * 保留为死字段会误导；如后续确需并发控制，在 {@code UnifiedModelGateway} 引入 try/finally 计数后补回。
 */
public class FlowControlPolicy {

    private final String id;
    private final int maxRequestsPerSecond;
    private final int maxTokensPerDay;
    private final Duration window;

    public FlowControlPolicy(String id, int maxRequestsPerSecond,
                             int maxTokensPerDay, Duration window) {
        this.id = id;
        this.maxRequestsPerSecond = maxRequestsPerSecond;
        this.maxTokensPerDay = maxTokensPerDay;
        this.window = window;
    }

    public String id() { return id; }
    public int maxRequestsPerSecond() { return maxRequestsPerSecond; }
    public int maxTokensPerDay() { return maxTokensPerDay; }
    public Duration window() { return window; }
}
