package com.agentdemo007.eval;

/**
 * 评测期望（Phase 15·T69 golden 数据集每例的 expected 子集，§5.14 强类型收口，非 Map）。
 *
 * <p>各 stage 的 expected 字段异构（injection 用 scenario+blocked、intent 用 intent+zeroLlm、
 * routing 用 route+selectedModel、degradation 用 outcome 等），故全部字段可空——
 * {@link EvalExecutor} 仅对照非空字段判定（缺失字段不参与比较）。
 *
 * <p>字段名对齐 eval/*.json 的 expected 键名，Jackson 直接按名映射。
 *
 * <p>Phase 20（T92）增可派生字段：{@code queryEnrichmentContains}（约束改写补全精确词）、
 * {@code ragFragmentsContain}（Hybrid 命中/时效标注文本子串）、{@code hasFragments}（片段非空）——
 * 使 ③环节测评 对 Phase 20 行为真正可断言（非文档字段被忽略）。
 *
 * @param scenario               降级场景名（NONE 表示无降级）
 * @param intent                 意图枚举名
 * @param route                  路由类型名（SIMPLE/REASONING/LONG_CONTEXT/STRUCTURED）
 * @param selectedModel          选定模型标识
 * @param degraded               是否降级
 * @param outcome                终态类型（PROCEED/SHORT_CIRCUIT/DEGRADE）
 * @param blocked                是否注入拦截
 * @param zeroLlm                是否零 LLM（短路在模型调用前）
 * @param shortCircuit           是否话术短路
 * @param escalatedToModel       是否上交小模型仲裁（执行器不派生，跳过比较）
 * @param queryEnrichmentContains 期望补全槽含此精确词（Phase 20·T88 约束改写）
 * @param ragFragmentsContain    期望某 ragFragments 元素含此文本子串（T89 Hybrid 命中 / T90 时效标注）
 * @param hasFragments           期望 ragFragments 是否非空
 */
public record EvalExpected(String scenario, String intent, String route, String selectedModel,
                           Boolean degraded, String outcome, Boolean blocked,
                           Boolean zeroLlm, Boolean shortCircuit, Boolean escalatedToModel,
                           String queryEnrichmentContains, String ragFragmentsContain, Boolean hasFragments) {

    /** 既有 10 参构造（Phase 15 调用方零改动；新字段缺省 null 不参与比较）。 */
    public EvalExpected(String scenario, String intent, String route, String selectedModel,
                        Boolean degraded, String outcome, Boolean blocked,
                        Boolean zeroLlm, Boolean shortCircuit, Boolean escalatedToModel) {
        this(scenario, intent, route, selectedModel, degraded, outcome, blocked,
                zeroLlm, shortCircuit, escalatedToModel, null, null, null);
    }
}
