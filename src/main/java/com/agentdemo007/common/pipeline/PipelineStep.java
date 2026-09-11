package com.agentdemo007.common.pipeline;

/**
 * 流水线步骤接口（收口契约）。
 *
 * <p>第一层接入安全 → 第二层会话理解 → 第三层意图路由 → 第四层能力(RAG/工具/HITL)
 * → 第五层上下文构建 → 第六层模型网关 → 第七层结构化输出，全部实现本接口：
 * 通过 {@link PipelineContext} 读写同一份状态，返回同一 {@link StepOutcome}。
 *
 * <p>步骤间不私造数据结构互相传参——一切状态经 {@code PipelineContext} 收口（防漂移）。
 * 实现标注 {@code @Order}（或实现 {@link org.springframework.core.Ordered}）控制执行顺序，
 * 由 {@link PipelineOrchestrator} 按序自动收集驱动。
 *
 * <p>LangGraph 适配（Phase 14）：本契约即 LangGraph 节点适配层，节点入参/出参都走
 * {@code PipelineContext}+{@code StepOutcome}，换引擎不换收口。
 */
public interface PipelineStep {

    /** 处理上下文，返回统一产出（Proceed/ShortCircuit/Degrade）。 */
    StepOutcome process(PipelineContext context);

    /** 步骤名，默认类名（审计/日志用）。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
