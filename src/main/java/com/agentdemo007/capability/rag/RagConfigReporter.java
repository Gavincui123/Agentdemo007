package com.agentdemo007.capability.rag;

import com.agentdemo007.gateway.config.LlmConfigReporter;

import java.util.List;

/**
 * RAG 检索配置启动报告器（Phase 21·混合检索可观测）。
 *
 * <p>把 {@link EmbeddingProperties}/{@link RerankerProperties}（raw provider 配置）渲染成密钥脱敏的
 * 多行文本，供 {@link RagConfigBootstrap} 启动时 INFO 打印——让「Nacos/env 读到了什么」可见可审
 * （哪几个 embedding/reranker provider、什么 model、key 指纹、降级策略）。脱敏复用
 * {@link LlmConfigReporter#maskKey}（尾4+长度，不落明文）。props 为 null → dev 占位分支。
 *
 * <p>纯函数（无 Spring/IO），可单测。
 */
public final class RagConfigReporter {

    private RagConfigReporter() {}

    public static String report(EmbeddingProperties emb, RerankerProperties rer) {
        StringBuilder sb = new StringBuilder();
        sb.append("RAG 检索配置：稀疏召回=BM25 本地（全语料打分，Bm25Retriever，不调 API）");
        sb.append("\n稠密召回=").append(emb == null
                ? "dev HashEmbedding（假向量，不调 API；embedding.enabled=true 时由 EmbeddingConfig 装配真嵌入+主备）"
                : embedReport(emb));
        sb.append("\n重排=").append(rer == null
                ? "dev Bm25Reranker（BM25-lite 本地；reranker.enabled=true 时由 RerankerConfig 装配真模型重排+主备）"
                : rerankerReport(rer));
        return sb.toString();
    }

    private static String embedReport(EmbeddingProperties props) {
        StringBuilder sb = new StringBuilder();
        List<EmbeddingProperties.Provider> ps = props.getProviders();
        sb.append("真 SiliconFlow 嵌入+主备(").append(ps == null ? 0 : ps.size())
                .append(" provider，主备全失败→HybridRetriever 降级稀疏-only)");
        sb.append("\nembedding providers:");
        if (ps == null || ps.isEmpty()) {
            sb.append(" (空——embedding.enabled=true 但 providers 未配置→稠密恒抛→降级稀疏)");
        } else {
            for (int i = 0; i < ps.size(); i++) {
                EmbeddingProperties.Provider p = ps.get(i);
                sb.append("\n  [").append(i == 0 ? "主" : "备").append("] id=").append(p.getId())
                        .append(" base-url=").append(p.getBaseUrl())
                        .append(" api-key=").append(LlmConfigReporter.maskKey(p.getApiKey()))
                        .append(" model=").append(p.getModel());
            }
        }
        return sb.toString();
    }

    private static String rerankerReport(RerankerProperties props) {
        StringBuilder sb = new StringBuilder();
        List<RerankerProperties.Provider> ps = props.getProviders();
        sb.append("真 SiliconFlow 重排+主备(").append(ps == null ? 0 : ps.size())
                .append(" provider，主备全失败→降级 BM25)");
        sb.append("\nreranker providers:");
        if (ps == null || ps.isEmpty()) {
            sb.append(" (空——reranker.enabled=true 但 providers 未配置→重排恒抛→降级 BM25)");
        } else {
            for (int i = 0; i < ps.size(); i++) {
                RerankerProperties.Provider p = ps.get(i);
                sb.append("\n  [").append(i == 0 ? "主" : "备").append("] id=").append(p.getId())
                        .append(" base-url=").append(p.getBaseUrl())
                        .append(" api-key=").append(LlmConfigReporter.maskKey(p.getApiKey()))
                        .append(" model=").append(p.getModel());
            }
        }
        return sb.toString();
    }
}
