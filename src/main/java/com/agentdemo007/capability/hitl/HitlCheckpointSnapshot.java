package com.agentdemo007.capability.hitl;

/**
 * HITL 挂起检查点快照（L2 挂起-恢复·2026-09-18）：挂起时刻恢复执行所需的<b>最小强类型上下文</b>。
 *
 * <p>StateGraph 语义上的断点保存：HitlStep(610) 触发挂起即落库（hitl_checkpoint 表，异步），
 * 审批通过后 {@code HitlResumeService} 据此重建 {@code PipelineContext} 并从 610 步之后恢复执行。
 * 不序列化整个 {@code PipelineContext}（含大量运行时对象，且恢复执行时工具/RAG 事实应重新取数
 * ——只快照"身份 + 输入 + 路由决策"：
 * <ul>
 *   <li>身份：traceId（血缘关联）/sessionId/userId/hitlTicketId/idempotencyKey（业务锚点）；</li>
 *   <li>输入：rawInput + standardQuery 文本（恢复执行的查询语义）；</li>
 *   <li>路由决策：intent 名 + routePlan JSON（650 工具/660 RAG/670 工作流门控全依赖它，
 *       不重跑路由——挂起时已定的能力决策不漂移）；</li>
 *   <li>时刻：pausedAtMs（审计/超时窗口核算）。</li>
 * </ul>
 *
 * @param ticketId         挂起工单 id（checkpoint 关联键）
 * @param idempotencyKey   业务幂等键（hitl:{action}:{订单号}——漂移校验锚点，跨会话稳定）
 * @param traceId          挂起请求的 traceId（血缘关联，恢复执行生成新 trace）
 * @param sessionId        会话标识
 * @param userId           当前用户 id（可空）
 * @param rawInput         用户原话
 * @param standardQueryText 标准化 Query 文本（可空）
 * @param intentName       意图枚举名（可空）
 * @param routePlanJson    RoutePlan JSON（可空=null 规划）
 * @param pausedAtMs       挂起时刻（epoch ms）
 */
public record HitlCheckpointSnapshot(
        String ticketId,
        String idempotencyKey,
        String traceId,
        String sessionId,
        String userId,
        String rawInput,
        String standardQueryText,
        String intentName,
        String routePlanJson,
        long pausedAtMs) {
}
