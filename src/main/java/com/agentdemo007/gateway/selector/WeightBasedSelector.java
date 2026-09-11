package com.agentdemo007.gateway.selector;

import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.exception.ModelSelectionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 权重选择策略：按 {@code weight} 加权抽样（权重越大命中概率越高）。
 *
 * <p>{@link Random} 可注入以便测试断言确定性边界；生产用 {@code new Random()}。
 */
public class WeightBasedSelector implements ModelSelector {

    private final Random random;

    public WeightBasedSelector() {
        this(new Random());
    }

    public WeightBasedSelector(Random random) {
        this.random = random;
    }

    @Override
    public ModelMetadata select(List<ModelMetadata> candidates, SelectionCriteria criteria) {
        List<ModelMetadata> usable = enabled(candidates);
        if (usable.isEmpty()) {
            throw new ModelSelectionException("无可用模型候选");
        }
        int total = 0;
        for (ModelMetadata m : usable) {
            total += Math.max(m.weight(), 1);
        }
        int draw = random.nextInt(total);
        int cumulative = 0;
        for (ModelMetadata m : usable) {
            cumulative += Math.max(m.weight(), 1);
            if (draw < cumulative) {
                return m;
            }
        }
        return usable.get(usable.size() - 1); // 浮点/边界兜底
    }

    private List<ModelMetadata> enabled(List<ModelMetadata> candidates) {
        List<ModelMetadata> result = new ArrayList<>();
        for (ModelMetadata m : candidates) {
            if (m.isEnabled()) result.add(m);
        }
        return result;
    }
}
