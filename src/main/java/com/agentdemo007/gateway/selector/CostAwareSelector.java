package com.agentdemo007.gateway.selector;

import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.exception.ModelSelectionException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 成本感知策略：选 {@code costPer1KTokens} 最低者（同成本取首个，稳定）。
 */
public class CostAwareSelector implements ModelSelector {

    @Override
    public ModelMetadata select(List<ModelMetadata> candidates, SelectionCriteria criteria) {
        List<ModelMetadata> usable = enabled(candidates);
        if (usable.isEmpty()) {
            throw new ModelSelectionException("无可用模型候选");
        }
        return usable.stream()
                .min(Comparator.comparingDouble(ModelMetadata::costPer1KTokens))
                .orElseThrow();
    }

    private List<ModelMetadata> enabled(List<ModelMetadata> candidates) {
        List<ModelMetadata> result = new ArrayList<>();
        for (ModelMetadata m : candidates) {
            if (m.isEnabled()) result.add(m);
        }
        return result;
    }
}
