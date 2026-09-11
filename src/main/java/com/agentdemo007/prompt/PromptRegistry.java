package com.agentdemo007.prompt;

import java.util.Optional;

/**
 * 提示词注册中心（收口入口）：按 promptKey + 版本规格取模板。
 *
 * <p>dev {@link LocalPromptSource}（内存固定，喂 eval 黄金样例确定性，③环节测评）；
 * prod {@code NacosPromptSource}（typed {@code AiService}，md5 缓存由 SDK 内置，
 * Nacos 不可达返回 empty 降级，②每步降级）。
 *
 * <p>获取失败（empty）时调用方应回退本地内置默认模板，不阻塞链路。
 */
public interface PromptRegistry {

    Optional<PromptTemplate> get(String promptKey, VersionSpec spec);
}
