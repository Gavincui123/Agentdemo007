package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排器测试（第四层·BM25-lite，证明重排顺序优于纯余弦召回）。
 *
 * <p>dev 用 BM25-lite（IDF + tf 饱和 + 长度归一）在候选集上重排；prod 覆盖为 Cross-Encoder / 模型重排。
 * 经典反例：查询 "退款流程"，语料中 退款（高频低 IDF）被某片段锤击、流程（稀有高 IDF）仅一处——
 * 纯余弦被词频锤击误导（spammy 片段排首），BM25 因稀有词高 IDF 上浮真正含流程的片段，顺序更优。
 */
class RerankerTest {

    private final HashEmbeddingService embedding = new HashEmbeddingService();
    private final InMemoryVectorStore store = new InMemoryVectorStore(embedding);
    private final VectorRetriever retriever = new VectorRetriever(embedding, store);
    private final Reranker reranker = new Bm25Reranker();

    private static RagFragment frag(String text) {
        return new RagFragment(text, 0.0, "test");
    }

    @Test
    void rerankBeatsPureCosineRecall() {
        store.index(List.of(
                frag("退款退款退款退款"),  // 锤击 退款（高频低 IDF）
                frag("退款说明"),
                frag("退款政策"),
                frag("流程办理须知")));  // 唯一含 流程（稀有高 IDF）
        String query = "退款流程";

        List<RagFragment> recall = retriever.retrieve(query, 4);
        // 纯余弦召回：词频锤击的 spammy 片段排首
        assertThat(recall).isNotEmpty();
        assertThat(recall.get(0).text()).isEqualTo("退款退款退款退款");

        List<RagFragment> reranked = reranker.rerank(query, recall);
        // BM25：稀有词 流程 IDF 高 → 含流程的片段上浮至首位
        assertThat(reranked.get(0).text()).isEqualTo("流程办理须知");
        int idxRare = indexOf(reranked, "流程办理须知");
        int idxSpam = indexOf(reranked, "退款退款退款退款");
        assertThat(idxRare).isLessThan(idxSpam);
    }

    @Test
    void blankQuery_keepsCandidateOrder() {
        List<RagFragment> candidates = List.of(frag("a"), frag("b"), frag("c"));
        List<RagFragment> out = reranker.rerank("", candidates);
        assertThat(out).extracting(RagFragment::text).containsExactly("a", "b", "c");
    }

    @Test
    void nullCandidates_returnsEmpty() {
        assertThat(reranker.rerank("退款", null)).isEmpty();
    }

    @Test
    void emptyCandidates_returnsEmpty() {
        assertThat(reranker.rerank("退款", List.of())).isEmpty();
    }

    @Test
    void singleCandidate_unchanged() {
        List<RagFragment> candidates = List.of(frag("退款政策"));
        List<RagFragment> out = reranker.rerank("退款", candidates);
        assertThat(out).extracting(RagFragment::text).containsExactly("退款政策");
    }

    private static int indexOf(List<RagFragment> frags, String text) {
        for (int i = 0; i < frags.size(); i++) {
            if (frags.get(i).text().equals(text)) {
                return i;
            }
        }
        return -1;
    }
}
