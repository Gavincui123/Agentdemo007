package com.agentdemo007.capability.rag;

import java.util.List;

/**
 * 语料库 seam（第四层·稀疏检索全语料来源，引擎无关抽象）。
 *
 * <p>暴露全部已索引片段供 {@link Bm25Retriever} 做 BM25 全语料打分召回。dev 由
 * {@link InMemoryVectorStore} 兼任（implements 本接口，单库双用：稠密余弦 + 稀疏 BM25 同库）；
 * prod 拆独立 BM25/ES 稀疏索引（换本接口实现即可，召回出口 {@link Retriever} 不变）。
 */
public interface RagCorpus {

    /** 全部已索引片段（按索引序；调用方自行打分排序）。 */
    List<RagFragment> fragments();
}
