package com.agentdemo007.resilience;

/**
 * 工具熔断开异常（Phase 17·T74）——per-tool 断路器 OPEN 时由 {@code ToolExecutor} 抛出，
 * 由 {@code ToolExecutionStep}（T75）收口为 {@code ShortCircuit(TOOL_FAILURE)} 话术短路
 * （HTTP 200，零 LLM，①话术短路 + ②每步降级不阻塞主链路）。
 *
 * <p>区别于 {@link ToolRecoverableException}（自纠正耗尽）：熔断中工具不可用，非参数可纠正，
 * 不进自纠正循环——直接短路兜底，等冷却后半开探针恢复。
 */
public class ToolCircuitOpenException extends ResilienceException {

    private final String toolName;

    public ToolCircuitOpenException(String toolName) {
        super("工具熔断中: " + toolName);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
