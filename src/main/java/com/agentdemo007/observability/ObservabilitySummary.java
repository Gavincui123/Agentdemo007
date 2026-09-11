package com.agentdemo007.observability;

import java.util.Map;

/**
 * 可观测快照 DTO（Phase 19·T99·④统一收口）。
 *
 * <p>强类型对外投影——由 {@link ObservabilitySummaryCollector} 从实时
 * {@link io.micrometer.core.instrument.MeterRegistry} 计数器（{@link AgentMetrics} 同实例稳定命名）
 * + 模型/HITL/会话结构性计数 派生，供运维台展示。禁止散漏 Map 领域语义（§5.14）；
 * {@code degradationByScenario} 是"场景维度→计数"的结构化映射，非领域对象，属可接受的分桶外泄。
 *
 * <p>字段分两组：
 * <ul>
 *   <li>指标组（{@code long}）：从 {@code MeterRegistry} counter 聚合——chatRequests/degradation/
 *       outcome(ok/degraded/shortCircuit)/rag(hit/miss)/tool(failure)/hitl(triggered)/failover(exhausted)。</li>
 *   <li>结构组（{@code int}/{@code long}）：模型注册数/启用数、PENDING 工单数、会话轮次总数/降级轮次数。</li>
 * </ul>
 * 降级事件计数（{@code degradationTotal}+{@code degradationByScenario}）与降级轮次计数
 * （{@code degradedTurns}）语义不同：前者是埋点事件（可一请求多场景），后者是持久化事实行数。
 *
 * @param chatRequests         接入层对话请求数（{@code agent.chat.requests}）
 * @param degradationTotal     降级事件总次数（{@code agent.degradation} 全场景聚合）
 * @param degradationByScenario 按场景分桶的降级事件计数（key=场景 name）
 * @param outcomeOk           正常完成请求数（{@code agent.pipeline.outcome outcome=ok}）
 * @param outcomeDegraded      降级完成请求数（{@code outcome=degraded}）
 * @param outcomeShortCircuit  话术短路请求数（{@code outcome=short_circuit}，①话术短路）
 * @param ragHit               RAG 注入命中数（{@code agent.rag hit=true}）
 * @param ragMiss              RAG 跳过数（{@code hit=false}）
 * @param toolFailure          工具执行失败数（{@code agent.tool success=false}）
 * @param hitlTriggered        HITL 转人工短路数（{@code agent.hitl triggered=true}）
 * @param failoverExhausted    故障转移候选耗尽数（{@code agent.failover outcome=exhausted}）
 * @param modelCount           已注册模型总数（含禁用）
 * @param modelEnabled         启用模型数（参与路由）
 * @param hitlPendingTickets   PENDING 工单数（待人工处理）
 * @param totalTurns           会话轮次总数（持久化事实行数）
 * @param degradedTurns        降级轮次数（{@code degraded=true} 行数）
 */
public record ObservabilitySummary(
        long chatRequests,
        long degradationTotal,
        Map<String, Long> degradationByScenario,
        long outcomeOk,
        long outcomeDegraded,
        long outcomeShortCircuit,
        long ragHit,
        long ragMiss,
        long toolFailure,
        long hitlTriggered,
        long failoverExhausted,
        int modelCount,
        int modelEnabled,
        int hitlPendingTickets,
        long totalTurns,
        long degradedTurns) {
}
