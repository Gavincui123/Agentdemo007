package com.agentdemo007.capability.tool;

/**
 * 工具调用失败分类（Phase 9 工具韧性·错误回喂 LLM 的强类型收口，§5.14）。
 *
 * <p>每类对应明确的应对策略（重试/不重试 × 是否记熔断），由 {@code ResilientToolExecutor}
 * 分类收口、{@code ToolCallExecutor} Agent loop 据此决定是否回喂探测 LLM 自纠正：
 * <ul>
 *   <li>{@code PARAM_INVALID} — 模型产出的参数不合法（JSON 解析/强转失败）：不重试、不记熔断
 *      （模型侧问题，工具健康），错误回喂探测 LLM 自纠正或向客户澄清；</li>
 *   <li>{@code UNKNOWN_TOOL} — 模型幻觉工具名：不重试、不记熔断，错误回喂探测 LLM 改选工具；</li>
 *   <li>{@code TIMEOUT} — 工具执行超时：瞬态可重试（指数退避+全抖动），耗尽记熔断；</li>
 *   <li>{@code HTTP_5XX} — 外部系统服务端错误：瞬态可重试，耗尽记熔断；</li>
 *   <li>{@code HTTP_4XX} — 外部系统明确拒绝（鉴权/参数/不存在）：不重试、不记熔断
 *       （外部系统有应答=服务健康），错误回喂 LLM 换策略；</li>
 *   <li>{@code TOOL_EXCEPTION} — 其他工具内部异常：按分诊重试（未知异常乐观重试），耗尽记熔断。</li>
 * </ul>
 */
public enum ToolErrorKind {
    PARAM_INVALID,
    UNKNOWN_TOOL,
    TIMEOUT,
    HTTP_4XX,
    HTTP_5XX,
    TOOL_EXCEPTION
}
