package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FailoverReranker} 单测（重排主备容灾 + BM25 降级）。
 *
 * <p>断言：主成功即返回（备不调）；主失败→切备；主备全失败→降级 BM25（重排只改顺序，安全）；
 * 空查询保留召回序；空候选→空。
 */
class FailoverRerankerTest {

    private static RagFragment frag(String t) {
        return new RagFragment(t, 0.0, "c");
    }

    @Test
    void primarySucceeds_backupNotCalled() {
        Reranker primary = (q, c) -> List.of(c.get(1), c.get(0));
        Reranker backup = (q, c) -> { throw new AssertionError("backup 不应被调"); };
        FailoverReranker fr = new FailoverReranker(List.of(primary, backup), new Bm25Reranker());

        List<RagFragment> out = fr.rerank("q", List.of(frag("A"), frag("B")));

        assertThat(out).extracting(RagFragment::text).containsExactly("B", "A");
    }

    @Test
    void primaryFails_backupSucceeds() {
        Reranker primary = (q, c) -> { throw new RuntimeException("主挂"); };
        Reranker backup = (q, c) -> List.of(c.get(0));
        FailoverReranker fr = new FailoverReranker(List.of(primary, backup), new Bm25Reranker());

        List<RagFragment> out = fr.rerank("q", List.of(frag("A"), frag("B")));

        assertThat(out).extracting(RagFragment::text).containsExactly("A");
    }

    @Test
    void allApiFail_degradesToBm25Fallback() {
        Reranker throwing = (q, c) -> { throw new RuntimeException("rerank api down"); };
        FailoverReranker fr = new FailoverReranker(List.of(throwing), new Bm25Reranker());
        // 退款 在 3 片段常见（低 IDF），流程 仅 1 片段（高 IDF）——BM25 使稀有词片段上浮
        List<RagFragment> candidates = List.of(
                new RagFragment("退款退款退款退款", 0.0, "c"),
                new RagFragment("退款说明", 0.0, "c"),
                new RagFragment("退款政策", 0.0, "c"),
                new RagFragment("流程办理须知", 0.0, "c"));

        List<RagFragment> out = fr.rerank("退款流程", candidates);

        assertThat(out.get(0).text()).isEqualTo("流程办理须知"); // BM25 稀有词 流程 高 IDF 上浮
    }

    @Test
    void blankQuery_preservesOrder_noApiCall() {
        Reranker primary = (q, c) -> { throw new AssertionError("空查询不应调 API"); };
        FailoverReranker fr = new FailoverReranker(List.of(primary), new Bm25Reranker());

        List<RagFragment> out = fr.rerank("", List.of(frag("A"), frag("B")));

        assertThat(out).extracting(RagFragment::text).containsExactly("A", "B");
    }

    @Test
    void emptyCandidates_returnsEmpty() {
        FailoverReranker fr = new FailoverReranker(List.of(), new Bm25Reranker());
        assertThat(fr.rerank("q", List.of())).isEmpty();
    }
}
