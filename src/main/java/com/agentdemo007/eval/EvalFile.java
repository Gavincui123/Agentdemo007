package com.agentdemo007.eval;

import java.util.List;

/**
 * 评测文件（Phase 15·T69 一个 stage 的 golden 数据集，对应 resources/eval/{stage}.json）。
 *
 * <p>本地图与 Nacos 图同构（2026-09-17 评测数据 Nacos 动态化）：Nacos dataId
 * {@code <prefix>-<stage>.json} 内容与本文件完全同形，改 Nacos 不重启、读取失败回退本地。
 *
 * @param stage       stage 名（injection/intent/routing/rag/tool/hitl/degradation/audit…）
 * @param description 描述
 * @param cases       用例列表
 * @param source      内容来源标签（nacos/local），由 {@link EvalContentResolver} 注入；报告透传
 */
public record EvalFile(String stage, String description, List<EvalCase> cases, String source) {

    /** 兼容构造（source=local）：既有测试/本地 classpath 加载用。 */
    public EvalFile(String stage, String description, List<EvalCase> cases) {
        this(stage, description, cases, EvalContentResolver.SOURCE_LOCAL);
    }
}
