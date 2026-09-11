package com.agentdemo007.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 模型配置启动加载器（Phase 12·启动即 refresh）。
 *
 * <p>Spring 启动完成后触发 {@link ModelConfigCenter#refresh()}，把 {@link ModelConfigSource}
 * 的快照整表落地到 {@link com.agentdemo007.gateway.registry.ModelRegistry}。dev 源内置
 * {@code dev-noop} 占位模型；prod Nacos 源就绪后同样由此处触发首次加载（后续由 Nacos 监听增量刷新）。
 *
 * <p>启动可观测：refresh 后经 {@link LlmConfigReporter} 把 {@link LlmProperties}（raw provider 配置）
 * + 当前快照渲染成<b>密钥脱敏</b>的多行报告 INFO 打印——让「Nacos/env 读到了什么」可见可审
 * （否则只有「启用模型 N 个」、谁都不知道具体 provider/模型/路由/熔断参数）。脱敏只显尾 4 + 长度，
 * 不 dump 原始 dataId YAML（含明文密钥会泄密）。llm.enabled=false 时 LlmProperties bean 不存在
 * （{@code ObjectProvider} 为空）→ 走 dev 占位源分支。
 *
 * <p>每步降级：加载失败不阻塞启动——日志告警、注册表维持空，下游网关选不到模型时收口为
 * {@link com.agentdemo007.common.degradation.DegradationScenario#MODEL_DOWN} 话术短路。
 */
@Component
public class ModelConfigBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigBootstrap.class);

    private final ModelConfigCenter center;
    private final ObjectProvider<LlmProperties> llmPropertiesProvider;

    /** 装配路径：注入 LlmProperties 的 ObjectProvider（llm.enabled=true 有 bean；false 为空 provider）。 */
    @Autowired
    public ModelConfigBootstrap(ModelConfigCenter center, ObjectProvider<LlmProperties> llmPropertiesProvider) {
        this.center = center;
        this.llmPropertiesProvider = llmPropertiesProvider;
    }

    /** 测试/dev 便捷构造（无 LlmProperties → 报告走 dev 占位源分支）。 */
    public ModelConfigBootstrap(ModelConfigCenter center) {
        this(center, null);
    }

    @Override
    public void run(String... args) {
        try {
            center.refresh();
            LlmProperties props = llmPropertiesProvider == null ? null : llmPropertiesProvider.getIfAvailable();
            log.info("{}", LlmConfigReporter.report(props, center.snapshot()));
        } catch (Exception e) {
            log.warn("模型配置加载失败，不阻塞启动（注册表为空，下游收口 MODEL_DOWN）：{}", e.getMessage()); // 审计
        }
    }
}
