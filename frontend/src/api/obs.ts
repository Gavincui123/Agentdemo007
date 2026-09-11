import { http } from './http'

/**
 * 可观测 API 模块（Phase 19·T102）。
 *
 * <p>对接后端 {@code GET /api/obs/summary}（T99）：实时聚合 {@link AgentMetrics} 稳定命名计数器
 * + 模型/HITL/会话结构性计数，经 {@link ObservabilitySummary} 强类型对外收口（④统一收口），
 * 供可观测台 ECharts 展示。
 *
 * <p>后端 {@link com.agentdemo007.observability.ObservabilitySummaryCollector} 每次请求实时读
 * {@code MeterRegistry}（dev {@code SimpleMeterRegistry} 内存计数，无后端），不缓存。
 * 空指标→0 / 空 Map（②每步降级：端点恒可用，不抛）。经 {@link http} 拦截器解包 {@code UnifiedResponse}，
 * 失败（如网络）由拦截器抛 {@code ApiError}（话术 message），由视图 catch 展示。
 *
 * <p>16 字段一一映射后端 record（字段名严格对齐，禁 Map 除外泄桶 {@code degradationByScenario}）。
 */

/**
 * 可观测快照——对齐后端 {@code ObservabilitySummary}（16 字段）。
 *
 * <p>{@code degradationByScenario} 为结构化桶（scenario→计数），非 domain 泄漏——
 * 值为后端 {@code Scenario} 枚举名（RAG_SKIP / TOOL_FAILURE / SHORT_CIRCUIT 等）。
 */
export interface ObservabilitySummary {
  chatRequests: number
  degradationTotal: number
  degradationByScenario: Record<string, number>
  outcomeOk: number
  outcomeDegraded: number
  outcomeShortCircuit: number
  ragHit: number
  ragMiss: number
  toolFailure: number
  hitlTriggered: number
  failoverExhausted: number
  modelCount: number
  modelEnabled: number
  hitlPendingTickets: number
  totalTurns: number
  degradedTurns: number
}

/** 全链路可观测快照：GET /api/obs/summary。空态→全 0 + 空 Map（②不抛）。 */
export async function getObservabilitySummary(): Promise<ObservabilitySummary> {
  return http.get('/api/obs/summary') as unknown as Promise<ObservabilitySummary>
}
