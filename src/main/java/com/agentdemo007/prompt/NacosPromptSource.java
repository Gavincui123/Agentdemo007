package com.agentdemo007.prompt;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.exception.NacosException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Nacos 提示词源（prod）：typed {@link AiService} 拉取，SDK 内置 md5 缓存 + gRPC 热推送。
 *
 * <p>版本规格分派：latest→{@code getPrompt} / version→{@code getPromptByVersion} /
 * label→{@code getPromptByLabel}；SDK {@link Prompt} 映射为收口 {@link PromptTemplate}（SDK 类型不外泄）。
 * Nacos 不可达（{@link NacosException}）或空返回 → {@code Optional.empty()}（②每步降级，
 * 调用方回退本地内置默认模板，不阻塞链路）。
 *
 * <p>{@link AiService} 由 {@code AiFactory.createAiService(Properties)} 构造后注入；
 * dev 无 Nacos 时不装配本类，回退 {@link LocalPromptSource}。
 */
public class NacosPromptSource implements PromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(NacosPromptSource.class);

    private final AiService aiService;

    public NacosPromptSource(AiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public Optional<PromptTemplate> get(String promptKey, VersionSpec spec) {
        try {
            Prompt p = fetch(promptKey, spec);
            if (p == null) {
                return Optional.empty();
            }
            return Optional.of(new PromptTemplate(
                    p.getPromptKey(), p.getVersion(), p.getTemplate(), p.getMd5()));
        } catch (NacosException e) {
            log.warn("Nacos 提示词拉取失败 (降级为 empty): promptKey={}, reason={}", promptKey, e.getMessage());
            return Optional.empty();
        }
    }

    private Prompt fetch(String promptKey, VersionSpec spec) throws NacosException {
        if (spec instanceof VersionSpec.Version v) {
            return aiService.getPromptByVersion(promptKey, v.value());
        }
        if (spec instanceof VersionSpec.Label l) {
            return aiService.getPromptByLabel(promptKey, l.value());
        }
        // Latest 或未知规格 → 跟随 latest
        return aiService.getPrompt(promptKey);
    }
}
