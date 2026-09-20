package com.agentdemo007.capability.rag.lucene;

import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.Retriever;

import java.util.List;

/**
 * Lucene BM25 稀疏检索器（真实 RAG·真库模式稀疏通道，{@code vectorstore.type=chroma} 时装配）。
 *
 * <p>实现 {@link Retriever} seam，委托 {@link LuceneBm25IndexService} 查磁盘倒排索引——
 * BM25 打分在 Lucene 内完成（BM25Similarity k1=1.2/b=0.75），JVM 不常驻语料（内存顾虑的生产解）。
 * 语义对齐 {@code Bm25Retriever}：稀疏是本地通道，异常上浮（RagStep 捕获 → RAG_SKIP），
 * 不静默吞错；空查询返回空。
 */
public class LuceneBm25Retriever implements Retriever {

    private final LuceneBm25IndexService indexService;

    public LuceneBm25Retriever(LuceneBm25IndexService indexService) {
        this.indexService = indexService;
    }

    @Override
    public List<RagFragment> retrieve(String query, int topK) {
        return indexService.search(query, topK);
    }
}
