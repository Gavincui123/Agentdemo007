package com.agentdemo007.prompt;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地提示词源（dev）：内存 Map 固定模板，不感知版本规格（dev 固定单一版本，保证 eval 确定性）。
 *
 * <p>由 {@code GatewayConfig} 以 {@code @ConditionalOnMissingBean} 装配为默认 {@link PromptRegistry}，
 * 保证无 Nacos 也可启动（②每步降级）；prod 由 {@code NacosPromptSource} 覆盖。
 */
public class LocalPromptSource implements PromptRegistry {

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();

    /** 注册/覆盖一个固定模板（测试与 eval 预置用）。 */
    public void put(String promptKey, PromptTemplate template) {
        templates.put(promptKey, template);
    }

    @Override
    public Optional<PromptTemplate> get(String promptKey, VersionSpec spec) {
        return Optional.ofNullable(templates.get(promptKey));
    }
}
