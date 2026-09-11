package com.agentdemo007.gateway.config;

import com.agentdemo007.gateway.registry.ModelRegistry;
import com.agentdemo007.intent.Intent;

import java.util.Optional;

/**
 * 模型配置中心（第六层·热加载 + 查询收口）。
 *
 * <p>从 {@link ModelConfigSource} 拉取 {@link ModelConfigSnapshot}，整表重建 {@link ModelRegistry}，
 * 并缓存当前快照供路由/流控/容灾查询。{@code refresh()} 可由 Nacos 监听触发（热加载，不重启）。
 * 收口：所有"当前模型配置"查询只经此中心，不散落读源。
 */
public class ModelConfigCenter {

    private final ModelConfigSource source;
    private final ModelRegistry registry;
    private volatile ModelConfigSnapshot current;

    public ModelConfigCenter(ModelConfigSource source, ModelRegistry registry) {
        this.source = source;
        this.registry = registry;
    }

    /** 拉取最新快照并整表重建注册表（热加载入口）。 */
    public synchronized void refresh() {
        ModelConfigSnapshot snapshot = source.load();
        registry.clear();
        if (snapshot != null && snapshot.models() != null) {
            for (ModelMetadata m : snapshot.models()) {
                registry.register(m);
            }
        }
        this.current = snapshot;
    }

    /**
     * 就地调整单个模型权重并立即落地到注册表（Phase 15·运维控制台热生效路径）。
     *
     * <p>经 {@link ModelMetadata#withWeight} 重建不可变副本后重新注册（同 id 覆盖），
     * 使新权重对 {@code WeightBasedSelector} 即时可读——无需重启、不等待下一次 {@link #refresh()}。
     * 返回是否命中并应用：未知模型返回 {@code false}（②降级，不抛）。
     *
     * <p>注意：本方法只改本进程内存的注册表，是"热生效"的即时路径；持久化与跨实例传播
     * 经 {@code ConfigWriter} 写回配置中心（prod Nacos）后由 {@link #refresh()} 收敛一致。
     * 在 dev（无 Nacos 写回）下，后续 {@link #refresh()} 会从源重新拉取并把权重还原为源值——
     * 这是预期语义：dev 的"热生效"仅在两次刷新之间成立。
     *
     * @param modelId 目标模型标识
     * @param weight  新权重（非负）
     * @return 模型命中并已应用返回 true；未知模型返回 false
     */
    public synchronized boolean applyWeight(String modelId, int weight) {
        if (modelId == null || modelId.isBlank() || weight < 0) {
            return false;
        }
        ModelMetadata existing = registry.get(modelId).orElse(null);
        if (existing == null) {
            return false;
        }
        registry.register(existing.withWeight(weight));
        return true;
    }

    /**
     * 热注册一个新模型到注册表（Phase 15·微调闭环 {@code ModelRegistrar} 的热生效路径）。
     *
     * <p>直接落地 {@link ModelRegistry#register}（同 id 覆盖），使新模型立即对 {@code ModelSelector}/
     * {@code ModelRouter} 可见可选中——无需重启、不等待 {@link #refresh()}。这是"热生效"的即时路径；
     * 持久化与跨实例传播经 {@code RegistrationWriter} 写回配置中心后由 {@link #refresh()} 收敛。
     *
     * @param metadata 新模型元数据（{@code null} 为 no-op，②降级）
     */
    public synchronized void registerModel(ModelMetadata metadata) {
        registry.register(metadata);
    }

    /** 暴露注册表（选择策略/网关读候选用）。 */
    public ModelRegistry registry() {
        return registry;
    }

    /** 按意图查路由规则。 */
    public Optional<RouteRule> routeFor(Intent intent) {
        if (current == null || current.routeRules() == null) {
            return Optional.empty();
        }
        for (RouteRule rule : current.routeRules()) {
            if (rule.intent() == intent) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** 当前流控策略（未加载则为 null）。 */
    public FlowControlPolicy flowControl() {
        return current == null ? null : current.flowControlPolicy();
    }

    /** 当前容灾策略（未加载则为 null）。 */
    public FailoverPolicy failover() {
        return current == null ? null : current.failoverPolicy();
    }

    /** 当前快照（启动报告/可观测用；refresh 前或失败为 null）。 */
    public ModelConfigSnapshot snapshot() {
        return current;
    }
}
