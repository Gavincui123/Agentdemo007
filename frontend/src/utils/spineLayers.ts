/**
 * 流水线步骤 → 七层脊下标映射（P0 可观测·聊天页逐步点亮）。
 *
 * <p>后端 {@code step_started}/{@code step_finished} 事件的 {@code step} 字段 =
 * {@code PipelineStep.name()}（默认类 SimpleName）。脊的七层（接入→会话→意图→能力→
 * 上下文→网关→输出）与真实步骤的对应关系：
 *
 * <ul>
 *   <li>0 接入：KeywordTriageStep（安全过滤 + 关键词分诊）；</li>
 *   <li>1 会话：SessionLoadStep / SessionRouter / QueryRewriter / RewriteQualityChecker；</li>
 *   <li>2 意图：IntentRecognitionStep / RouteDispatchStep / RoutePlanStep；</li>
 *   <li>3 能力：HitlStep / ToolExecutionStep / RagStep / WorkflowExecutionStep；</li>
 *   <li>4 上下文：ContextBuilder（三层上下文装配）；</li>
 *   <li>5 网关：无独立步骤——LLM 调用发生在意图/路由/输出各步内部，脊随宿主层点亮
 *       （active=输出层时 5 依 {@code i <= active} 连带亮起）；</li>
 *   <li>6 输出：OutputStep（结构化输出 + 流式 token）。</li>
 * </ul>
 *
 * <p>未知步骤名（后端新增步骤而前端未跟上）→ null：脊保持当前状态不动，不误跳层。
 */
const LAYER_BY_STEP: Record<string, number> = {
  KeywordTriageStep: 0,
  SessionLoadStep: 1,
  SessionRouter: 1,
  QueryRewriter: 1,
  RewriteQualityChecker: 1,
  IntentRecognitionStep: 2,
  RouteDispatchStep: 2,
  RoutePlanStep: 2,
  HitlStep: 3,
  ToolExecutionStep: 3,
  RagStep: 3,
  WorkflowExecutionStep: 3,
  ContextBuilder: 4,
  OutputStep: 6,
}

/** 步骤名 → 七层脊下标（0-6）；未知步骤 → null。 */
export function stepToLayer(step: string): number | null {
  return LAYER_BY_STEP[step] ?? null
}
