package com.agentdemo007.feedback;

import java.time.OffsetDateTime;

/**
 * 训练样本（Phase 15·离线数据池的强类型条目，§5.14 禁止 Map）。
 *
 * <p>由 {@link FeedbackCollector} 从在线反馈 + 当轮对话上下文组装，存入 {@link OfflineDataPool}，
 * 供 T68 {@code FineTuningPipeline} 拉取训练。{@code traceId}/{@code sessionId} 保留可追溯链路。
 *
 * @param traceId     当轮链路标识（与 {@code /chat} 响应的 traceId 对齐）
 * @param sessionId   会话标识
 * @param prompt      原始用户输入
 * @param reply       模型最终回复
 * @param label       反馈标签（正/负样本信号）
 * @param comment     可选人工备注（无则为 {@code null}）
 * @param collectedAt 采集时刻（ISO 偏移时间）
 */
public record TrainingSample(String traceId, String sessionId, String prompt, String reply,
                              FeedbackLabel label, String comment, OffsetDateTime collectedAt) {
}
