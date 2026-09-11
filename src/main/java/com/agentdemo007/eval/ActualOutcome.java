package com.agentdemo007.eval;

import java.util.List;

/**
 * 单例实际产出（Phase 15·T69，由 {@link PipelineResult}+{@link com.agentdemo007.common.pipeline.PipelineContext} 派生）。
 *
 * <p>承载可与 {@link EvalExpected} 对照的可派生字段：scenario/degraded/intent/route/selectedModel/
 * outcome/blocked/zeroLlm/shortCircuit。{@code escalatedToModel} 不在上下文可派生范围，故不在此（期望有则跳过比较）。
 *
 * <p>Phase 20（T92）增可派生字段：{@code queryEnrichmentKeywords}（约束改写补全精确词，来自
 * {@code context.queryEnrichment()}）、{@code ragFragments}（RAG 片段文本列表，含时效标注）、
 * {@code hasFragments}（片段非空）——使 ③环节测评 对 Phase 20 行为可断言。
 *
 * @param scenario      降级场景名（无降级为 {@code NONE}）
 * @param degraded      是否降级
 * @param intent        意图枚举名（未识别为 {@code null}）
 * @param route         路由类型名（未设为 {@code null}）
 * @param selectedModel 选定模型标识（未选为 {@code null}）
 * @param outcome       终态类型（PROCEED/SHORT_CIRCUIT/DEGRADE）
 * @param blocked       是否注入拦截（scenario=INJECTION）
 * @param zeroLlm       是否零 LLM（scenario 属短路在模型调用前的场景）
 * @param shortCircuit  是否话术短路（outcome=SHORT_CIRCUIT）
 * @param queryEnrichmentKeywords 约束改写补全精确词（Phase 20·T88）
 * @param ragFragments   RAG 片段文本列表（含时效标注，T90 displayText）
 * @param hasFragments   片段是否非空
 */
record ActualOutcome(String scenario, boolean degraded, String intent, String route, String selectedModel,
                     String outcome, boolean blocked, boolean zeroLlm, boolean shortCircuit,
                     List<String> queryEnrichmentKeywords, List<String> ragFragments, boolean hasFragments) {
}
