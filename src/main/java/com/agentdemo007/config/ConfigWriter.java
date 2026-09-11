package com.agentdemo007.config;

/**
 * 配置中心写回 seam（Phase 15·运维控制台调权热生效的持久化路径，§5.14 引擎无关内核）。
 *
 * <p>domain-typed 写回：{@link #writeModelWeight} 以强类型 (modelId, weight) 入参，不暴露
 * 配置中心的内容格式（YAML/properties）——prod Nacos 实现负责合并进当前配置并 {@code publishConfig}，
 * 由 Nacos 监听触发各实例 {@code ModelConfigCenter.refresh()} 收敛一致。
 *
 * <p>dev 默认 {@link #NO_OP} 返回 false（不持久化），"热生效"由 {@code ModelConfigCenter.applyWeight}
 * 的内存路径兜底；prod 装配 {@code NacosConfigWriter} 覆盖（{@code @ConditionalOnMissingBean}）。
 * 形态对齐 {@code TraceContextPropagator}/{@code MessagePublisher}：seam 在前，dev 占位，prod 后接。
 *
 * <p>②每步降级：实现可抛异常，调用方（{@code RouteWeightController}）吞而不回滚内存、不抛 5xx。
 */
@FunctionalInterface
public interface ConfigWriter {

    /**
     * 写回单个模型的权重到配置中心。
     *
     * @param modelId 目标模型标识
     * @param weight  新权重（非负）
     * @return 持久化成功返回 true；未持久化（dev 占位 / 失败）返回 false
     */
    boolean writeModelWeight(String modelId, int weight);

    /** dev 占位：不持久化，仅返回 false（热生效走 {@code ModelConfigCenter.applyWeight} 内存路径）。 */
    ConfigWriter NO_OP = (modelId, weight) -> false;
}
