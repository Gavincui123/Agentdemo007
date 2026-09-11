package com.agentdemo007.capability.tool;

import com.agentdemo007.resilience.ToolCircuitBreaker;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * LC4j 工具执行的韧性装饰器（option B 退役后的韧性 seam）。
 *
 * <p>包 {@link DefaultToolExecutor}（LC4j 原生：JSON 解析 + 参数强转 + 反射调 {@code @Tool}），
 * 在其上挂 per-tool 熔断（复用 {@link ToolCircuitBreaker}）：OPEN 快速失败不调底层；
 * 成功记账、失败累计。**执行原语归 LC4j，韧性归项目**——用依赖 + 保韧性不二选一
 *（[[dont-hardwrite-use-dep-methods]]：库方法优先；[[langchain4j-boot4-compat-findings]]：seam=委托非重写）。
 *
 * <p>降级/收口（{@code TOOL_FAILURE} 短路、{@code StepOutcome} 映射、话术）不在本层——
 * 由 {@code ToolExecutionStep} 统一收口（[[degradation-and-eval-principles]]：收口最重要），
 * 本装饰器只表达"执行一次 + 熔断记账"。
 */
public class ResilientToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final ToolCircuitBreaker breaker;

    public ResilientToolExecutor(ToolExecutor delegate, ToolCircuitBreaker breaker) {
        this.delegate = delegate;
        this.breaker = breaker;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String name = request.name();
        if (!breaker.allow(name)) {
            // OPEN 快速失败：零工具执行，抛出由上层 ToolExecutionStep 收口 TOOL_FAILURE
            throw new ToolCircuitOpenException("工具熔断中：" + name);
        }
        try {
            String result = delegate.execute(request, memoryId);
            breaker.recordSuccess(name);
            return result;
        } catch (RuntimeException e) {
            breaker.recordFailure(name);
            throw e;
        }
    }
}
