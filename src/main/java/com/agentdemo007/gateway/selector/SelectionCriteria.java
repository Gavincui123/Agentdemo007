package com.agentdemo007.gateway.selector;

/**
 * 模型选择条件（策略无关的中性载体）。
 *
 * <p>各 {@link ModelSelector} 实现按需读取字段：
 * {@link TagBasedSelector} 用 {@code requestedTag}；{@link WeightBasedSelector} 忽略标签按权重抽；
 * {@link CostAwareSelector} 忽略标签选最低成本。
 */
public record SelectionCriteria(String requestedTag, boolean preferCheapest) {

    public static SelectionCriteria byTag(String tag) {
        return new SelectionCriteria(tag, false);
    }

    public static SelectionCriteria weighted() {
        return new SelectionCriteria(null, false);
    }

    public static SelectionCriteria cheapest() {
        return new SelectionCriteria(null, true);
    }
}
