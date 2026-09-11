package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Bm25Retriever} 单测（真 BM25 稀疏召回——全语料打分，与稠密互补）。
 *
 * <p>断言：① BM25 稀疏召回使含稀有查询词的片段上浮（流程 IDF 高，优于纯词频锤击）；
 * ② topK 截断；③ 空查询/空语料→空；④ 经 {@link RagCorpus} seam 取语料（引擎无关）。
 */
class Bm25RetrieverTest {

    private final HashEmbeddingService embedding = new HashEmbeddingService();
    private final InMemoryVectorStore store = new InMemoryVectorStore(embedding);
    private final Bm25Retriever retriever = new Bm25Retriever(store, new Bm25Reranker());

    private static RagFragment frag(String text) {
        return new RagFragment(text, 0.0, "test");
    }

    @Test
    void retrieve_rareTermRanksHigh_bm25Sparse() {
        store.index(List.of(
                frag("退款退款退款退款"),   // 锤击 退款（高频低 IDF）
                frag("退款说明"),
                frag("退款政策"),
                frag("流程办理须知")));    // 唯一含 流程（稀有高 IDF）

        List<RagFragment> out = retriever.retrieve("退款流程", 4);

        assertThat(out).isNotEmpty();
        assertThat(out.get(0).text()).isEqualTo("流程办理须知"); // 稀有词 流程 高 IDF → 首位
    }

    @Test
    void retrieve_topKTruncates() {
        store.index(List.of(frag("退款政策"), frag("退款说明"), frag("退款流程")));
        List<RagFragment> out = retriever.retrieve("退款", 2);
        assertThat(out).hasSize(2);
    }

    @Test
    void blankQuery_returnsEmpty() {
        store.index(List.of(frag("退款流程")));
        assertThat(retriever.retrieve("", 5)).isEmpty();
        assertThat(retriever.retrieve(null, 5)).isEmpty();
    }

    @Test
    void emptyCorpus_returnsEmpty() {
        assertThat(retriever.retrieve("退款", 5)).isEmpty();
    }
}
