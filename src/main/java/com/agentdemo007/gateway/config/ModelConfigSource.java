package com.agentdemo007.gateway.config;

/**
 * 模型配置源（可插拔：dev 本地 / prod Nacos）。
 *
 * <p>实现负责产出 {@link ModelConfigSnapshot}；{@link ModelConfigCenter} 不感知来源，
 * 只在 {@code refresh()} 时整表拉取并落地。Nacos 不可达时实现可降级返回上次快照或空快照，不阻塞启动。
 */
@FunctionalInterface
public interface ModelConfigSource {

    ModelConfigSnapshot load();
}
