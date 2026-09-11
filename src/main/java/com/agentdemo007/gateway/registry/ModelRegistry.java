package com.agentdemo007.gateway.registry;

import com.agentdemo007.gateway.config.ModelMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表（第六层·模型元数据的数据真相源）。
 *
 * <p>由 {@code ModelConfigCenter} 热加载落地、由 {@code ModelSelector} 读取候选。
 * 收口：所有模型查询只经此表，避免散落集合；按 id 去重，同 id 注册即覆盖。
 * 线程安全（{@link ConcurrentHashMap}），支持微调闭环动态注册新模型端点。
 */
public class ModelRegistry {

    private final Map<String, ModelMetadata> models = new ConcurrentHashMap<>();

    /** 注册或覆盖（同 id）。 */
    public void register(ModelMetadata metadata) {
        if (metadata == null) return;
        models.put(metadata.id(), metadata);
    }

    /** 注销。 */
    public void unregister(String id) {
        models.remove(id);
    }

    /** 按 id 查询。 */
    public Optional<ModelMetadata> get(String id) {
        return Optional.ofNullable(models.get(id));
    }

    /** 全量快照。 */
    public List<ModelMetadata> all() {
        return new ArrayList<>(models.values());
    }

    /** 仅启用模型。 */
    public List<ModelMetadata> enabled() {
        List<ModelMetadata> result = new ArrayList<>();
        for (ModelMetadata m : models.values()) {
            if (m.isEnabled()) result.add(m);
        }
        return result;
    }

    /** 含指定标签的启用模型。 */
    public List<ModelMetadata> byTag(String tag) {
        List<ModelMetadata> result = new ArrayList<>();
        for (ModelMetadata m : models.values()) {
            if (m.isEnabled() && m.tags().contains(tag)) result.add(m);
        }
        return result;
    }

    /** 清空（热加载整表替换前用）。 */
    public void clear() {
        models.clear();
    }
}
