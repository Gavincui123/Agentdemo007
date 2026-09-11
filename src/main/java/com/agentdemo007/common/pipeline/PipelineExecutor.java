package com.agentdemo007.common.pipeline;

/**
 * 流水线执行器接口（Phase 14·编排模式收口）。
 *
 * <p>统一线性编排（{@link PipelineOrchestrator}）与图编排（{@code GraphExecutor}）的出口——
 * 调用方（{@code ChatController}）只依赖本接口，按配置在两实现间切换
 * （{@code agentdemo.pipeline.mode=linear|graph}，保留直接链路作为对照，§5.14 / 开发计划 Phase 14）。
 *
 * <p>④统一收口：换引擎不换出口——两实现都产出同一 {@link PipelineResult}，
 * 经同一 {@link PipelineContext} 读写状态；本接口是这条不变量的类型锚点。
 */
public interface PipelineExecutor {

    /** 驱动流水线，返回终态收口结果。 */
    PipelineResult run(PipelineContext context);
}
