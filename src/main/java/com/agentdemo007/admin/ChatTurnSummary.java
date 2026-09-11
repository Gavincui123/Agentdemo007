package com.agentdemo007.admin;

import java.time.OffsetDateTime;

/**
 * 会话轮次摘要 DTO（Phase 19·管理台对外收口）。
 *
 * <p>{@link com.agentdemo007.persistence.entity.ChatTurnEntity} 的只读投影，供会话历史回放展示。
 * 强类型字段对齐实体（§5.14：禁止 Map，实体→DTO 一一映射）。
 */
public record ChatTurnSummary(Long id, String traceId, String sessionId, String rawInput,
                              String finalReply, String intent, boolean degraded,
                              String scenario, OffsetDateTime timestamp) {
}
