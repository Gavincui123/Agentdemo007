package com.agentdemo007.capability.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * RAG 检索配置启动报告器（Phase 21·启动可观测，与 {@link com.agentdemo007.gateway.config.ModelConfigBootstrap} 同构收口）。
 *
 * <p>Spring 启动完成后经 {@link RagConfigReporter} 把 embedding/reranker provider 配置渲染成密钥脱敏报告
 * INFO 打印——让「Nacos/env 读到了什么」可见可审。embedding/reranker.enabled=false 时对应 Properties bean
 * 不存在（{@code ObjectProvider} 为空）→ 走 dev 占位分支。报告失败不阻塞启动。
 */
@Component
public class RagConfigBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RagConfigBootstrap.class);

    private final ObjectProvider<EmbeddingProperties> embeddingProvider;
    private final ObjectProvider<RerankerProperties> rerankerProvider;

    public RagConfigBootstrap(ObjectProvider<EmbeddingProperties> embeddingProvider,
                              ObjectProvider<RerankerProperties> rerankerProvider) {
        this.embeddingProvider = embeddingProvider;
        this.rerankerProvider = rerankerProvider;
    }

    @Override
    public void run(String... args) {
        try {
            EmbeddingProperties emb = embeddingProvider == null ? null : embeddingProvider.getIfAvailable();
            RerankerProperties rer = rerankerProvider == null ? null : rerankerProvider.getIfAvailable();
            log.info("{}", RagConfigReporter.report(emb, rer));
        } catch (Exception e) {
            log.warn("RAG 检索配置报告失败（不阻塞）：{}", e.getMessage());
        }
    }
}
