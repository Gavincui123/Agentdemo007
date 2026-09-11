package com.agentdemo007.common.degradation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 降级话术中心。
 *
 * <p>面向 C 端用户始终返回"正常"对话话术，不暴露技术错误码。
 * 默认话术内置在 {@link DegradationScenario}；本中心作为可注入的覆盖入口，
 * 后续由 Nacos 配置热更新覆盖（覆盖映射优先于枚举默认）。
 *
 * <p>短路调用方约定：返回话术后立即结束当前链路（审计 + 跳过后续步骤，零 LLM）。
 */
@Component
public class DegradationPhraseCenter {

    private final Map<DegradationScenario, String> overrides = new ConcurrentHashMap<>();

    /** 取话术：Nacos 覆盖优先，否则枚举默认。 */
    public String phrase(DegradationScenario scenario) {
        return overrides.getOrDefault(scenario, scenario.phrase());
    }

    /** 热更新覆盖（Nacos 配置变更回调）。 */
    public void putOverride(DegradationScenario scenario, String phrase) {
        overrides.put(scenario, phrase);
    }

    /** 清除覆盖（回退默认）。 */
    public void clearOverride(DegradationScenario scenario) {
        overrides.remove(scenario);
    }
}
