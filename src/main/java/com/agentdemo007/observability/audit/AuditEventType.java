package com.agentdemo007.observability.audit;

import com.agentdemo007.common.degradation.DegradationScenario;

/**
 * 审计事件类型枚举（Phase 13·独立审计链路）。
 *
 * <p>覆盖 §5.12 要求全量留痕的关键事件类别：注入检测、工具调用、HITL、故障转移、异常等。
 * 枚举名（{@code name()}）稳定，作为审计落库/序列化的持久标识——不可漂移为中文展示名，
 * 避免反序列化或跨环境对账时断裂。事件只增不改（append-only，§5.11 可审计性）。
 */
public enum AuditEventType {

    /** 接入层提示注入检测命中（零 LLM 短路）。 */
    INJECTION,
    /** 工具调用（参数/结果摘要留痕）。 */
    TOOL_CALL,
    /** 工具自纠正耗尽/失败（TOOL_FAILURE 话术短路）。 */
    TOOL_FAILURE,
    /** HITL 转人工/超时/权限不足（工单状态流转）。 */
    HITL,
    /** 模型故障转移（主→备切换）。 */
    FAILOVER,
    /** 无可用模型（MODEL_DOWN 短路）。 */
    MODEL_DOWN,
    /** 容灾耗尽（FAILOVER_EXHAUSTED 短路）。 */
    FAILOVER_EXHAUSTED,
    /** 会话缓存故障（SESSION_DOWN 短路）。 */
    SESSION_DOWN,
    /** 结构化输出降级（OUTPUT_FALLBACK）。 */
    OUTPUT_FALLBACK,
    /** RAG 跳过/召回空（不阻塞降级）。 */
    RAG_SKIP,
    /** 无依据拒答（[[refusal-design]]：知识 grounding 未命中 + strict 模式短路，业务级拒答非降级）。 */
    REFUSAL,
    /** 流水线步骤降级短路（DEGRADATION，通用降级留痕）。 */
    DEGRADATION,
    /** 未预期异常收口到 INTERNAL 话术（全局兜底）。 */
    EXCEPTION;

    /**
     * 降级场景 → 审计类型映射（§5.14 单一真相源收口）。
     *
     * <p>流水线编排器短路/异常收口时经此映射决定审计事件类型，避免各处自行 if/else 漂移。
     * 有专属类型的关键场景直映；无专属的通用降级（请求类/限流/未知意图）收口到 {@link #DEGRADATION}；
     * {@link DegradationScenario#INTERNAL}（步骤异常全局兜底）映为 {@link #EXCEPTION}。
     *
     * @param scenario 降级场景（null 视为通用降级→{@link #DEGRADATION}）
     */
    public static AuditEventType from(DegradationScenario scenario) {
        if (scenario == null) {
            return DEGRADATION;
        }
        return switch (scenario) {
            case INJECTION -> INJECTION;
            case TOOL_FAILURE -> TOOL_FAILURE;
            case HITL_TIMEOUT -> HITL;
            case FAILOVER_EXHAUSTED -> FAILOVER_EXHAUSTED;
            case MODEL_DOWN -> MODEL_DOWN;
            case SESSION_DOWN -> SESSION_DOWN;
            case OUTPUT_FALLBACK -> OUTPUT_FALLBACK;
            case RAG_SKIP -> RAG_SKIP;
            case INTERNAL -> EXCEPTION;
            default -> DEGRADATION;
        };
    }
}
