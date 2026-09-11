package com.agentdemo007.eval;

/**
 * 评测用例（Phase 15·T69 golden 数据集单例）。
 *
 * @param id       用例标识
 * @param input    用户输入（经 {@link com.agentdemo007.common.pipeline.PipelineExecutor} 跑）
 * @param expected 期望产出子集（仅非空字段参与比较）
 * @param note     说明（仅文档，不参与比较）
 *
 * <p>JSON 中的 {@code context} 前置条件字段由加载侧 {@code ObjectMapper} 配置
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 忽略——执行器只跑 {@code input}，
 * {@code context} 是环境条件的文档标注，不由执行器搭建。
 */
public record EvalCase(String id, String input, EvalExpected expected, String note) {
}
