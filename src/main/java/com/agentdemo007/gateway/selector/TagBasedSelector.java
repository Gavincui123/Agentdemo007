package com.agentdemo007.gateway.selector;

import com.agentdemo007.gateway.config.ModelMetadata;
import com.agentdemo007.gateway.exception.ModelSelectionException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 标签选择策略：按 {@code requestedTag} 过滤，命中者取权重最高（确定性）。
 */
public class TagBasedSelector implements ModelSelector {

    @Override
    public ModelMetadata select(List<ModelMetadata> candidates, SelectionCriteria criteria) {
        List<ModelMetadata> usable = enabled(candidates);
        if (usable.isEmpty()) {
            throw new ModelSelectionException("无可用模型候选");
        }
        String tag = criteria.requestedTag();
        List<ModelMetadata> matched = new ArrayList<>();
        for (ModelMetadata m : usable) {
            if (tag == null || m.tags().contains(tag)) matched.add(m);
        }
        if (matched.isEmpty()) {
            throw new ModelSelectionException("无匹配标签的模型：tag=" + tag);
        }
        matched.sort(Comparator.comparingInt(ModelMetadata::weight).reversed());
        return matched.get(0);
    }

    private List<ModelMetadata> enabled(List<ModelMetadata> candidates) {
        List<ModelMetadata> result = new ArrayList<>();
        for (ModelMetadata m : candidates) {
            if (m.isEnabled()) result.add(m);
        }
        return result;
    }
}
