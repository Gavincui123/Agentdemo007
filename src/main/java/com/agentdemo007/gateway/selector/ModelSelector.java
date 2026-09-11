package com.agentdemo007.gateway.selector;

import com.agentdemo007.gateway.config.ModelMetadata;

import java.util.List;

/**
 * 模型选择策略接口（第六层·可插拔，配置驱动切换）。
 *
 * <p>三种实现：{@link TagBasedSelector}（标签命中取最高权重）、
 * {@link WeightBasedSelector}（按权重加权抽样）、{@link CostAwareSelector}（最低成本）。
 * 输入为已过滤的启用候选集，输出单个模型；空集或无匹配 → {@link com.agentdemo007.gateway.exception.ModelSelectionException}。
 */
public interface ModelSelector {

    ModelMetadata select(List<ModelMetadata> candidates, SelectionCriteria criteria);
}
