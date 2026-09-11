package com.agentdemo007.eval;

import java.util.List;

/**
 * 评测文件（Phase 15·T69 一个 stage 的 golden 数据集，对应 resources/eval/{stage}.json）。
 *
 * @param stage       stage 名（injection/intent/routing/rag/tool/hitl/degradation/audit…）
 * @param description 描述
 * @param cases       用例列表
 */
public record EvalFile(String stage, String description, List<EvalCase> cases) {
}
