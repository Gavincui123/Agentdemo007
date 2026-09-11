package com.agentdemo007.feedback;

/**
 * 反馈请求（Phase 15·T67 在线反馈入参，第四原则·强类型收口，非 Map）。
 *
 * <p>客户端在收到 {@code /chat} 响应后，凭 {@code traceId} 回传对该轮的反馈 + 当轮对话上下文
 * （{@code prompt}/{@code reply} 由客户端持有，解耦于持久化层——prod 如需可换为按 traceId 查
 * {@code ChatTurnRepository} 的 lookup seam）。
 *
 * @param traceId   当轮链路标识（与 {@code /chat} 响应 traceId 对齐）
 * @param sessionId 会话标识
 * @param prompt    原始用户输入（训练样本 prompt）
 * @param reply     模型最终回复（训练样本 reply）
 * @param label     反馈标签（正/负样本信号）
 * @param comment   可选人工备注（无传 {@code null}）
 */
public record FeedbackRequest(String traceId, String sessionId, String prompt, String reply,
                               FeedbackLabel label, String comment) {
}
