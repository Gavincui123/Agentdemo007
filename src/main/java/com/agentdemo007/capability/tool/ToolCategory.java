package com.agentdemo007.capability.tool;

/**
 * 工具通道分类（[[business-tools-workflow-dag]] §2.2·用户钦定 3 通道）。
 *
 * <p>{@link ToolCallExecutor#execute} 返回的 {@link ToolCallResult} 带 category，
 * {@link ToolExecutionStep} 据此路由到不同 {@link com.agentdemo007.common.pipeline.PipelineContext} 字段：
 * <ul>
 *   <li>{@link #RUNTIME}——外部系统高置信事实（订单/用户/商品）→ {@code runtimeFacts}（System 锚点层 Runtime 块）；</li>
 *   <li>{@link #RAG}——知识库政策（退货/退款/活动）→ {@code ragFragments}+{@code ragCitations}（带 citation）；</li>
 *   <li>{@link #COMPUTE}——简单计算（面积/乘法）→ {@code toolResults}（现状不变）。</li>
 * </ul>
 * 缺省 COMPUTE（向后兼容既有无 {@link ToolChannel} 注解的计算工具）。
 */
public enum ToolCategory {
    RUNTIME,
    RAG,
    COMPUTE
}
