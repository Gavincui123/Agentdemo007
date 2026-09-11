package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重排器时效衰减测评（Phase 20·T91）。
 *
 * <p>{@link Reranker} 对历史片段（{@code temporalTag=HISTORICAL}）的 BM25 分施加衰减系数
 * ——**同等相关性近期优先**（§5.4.1 时效治理）：BM25 同分时当前片段排历史片段之上，
 * 过时不冒充当前。无 temporalTag/CURRENT 不衰减（既有行为不变，既有 {@code RerankerTest} 守）。
 *
 * <p>构造 BM25 等分对（"退款流程甲" vs "退款流程乙"，CJK 单字等长等词频）以隔离衰减效应。
 */
class RerankerTemporalDecayTest {

    private final Reranker reranker = new Bm25Reranker();

    @Test
    void equalRelevance_currentRanksAboveHistorical() {
        RagFragment current = new RagFragment("退款流程甲", 0.5, "doc1", null, null, "CURRENT");
        RagFragment historical = new RagFragment("退款流程乙", 0.5, "doc2",
                null, Instant.parse("2024-01-01T00:00:00Z"), "HISTORICAL");
        // 输入序：历史在前；无衰减时等分稳定排序应保留历史在前 → 衰减后当前置顶
        List<RagFragment> out = reranker.rerank("退款流程", List.of(historical, current));

        assertThat(out.get(0).text()).isEqualTo("退款流程甲"); // 当前优先
        assertThat(out.get(1).text()).isEqualTo("退款流程乙");
    }

    @Test
    void historicalAlone_stillReturned() {
        RagFragment historical = new RagFragment("退款流程", 0.5, "doc1",
                null, Instant.parse("2024-01-01T00:00:00Z"), "HISTORICAL");

        List<RagFragment> out = reranker.rerank("退款流程", List.of(historical));

        assertThat(out).hasSize(1); // 衰减不剔除，仅降权
    }

    @Test
    void noTemporalTag_noDecay_preservesInputOrderOnTie() {
        // 无 temporal → 不衰减；等分稳定排序保留输入序
        RagFragment a = new RagFragment("退款流程甲", 0.5, "doc1");
        RagFragment b = new RagFragment("退款流程乙", 0.5, "doc2");

        List<RagFragment> out = reranker.rerank("退款流程", List.of(b, a));

        assertThat(out).extracting(RagFragment::text).containsExactly("退款流程乙", "退款流程甲");
    }
}
