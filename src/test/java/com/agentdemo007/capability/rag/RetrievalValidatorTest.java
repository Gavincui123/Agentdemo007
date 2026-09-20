package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索置信度终闸测试（第四层·双判据：被远程重排的看 relevance，未重排的看 cosine）。
 *
 * <p>真实 RAG 硬约束：低置信知识绝不进 LLM。BM25 词面分/关键词命中数（{@code cosineScored=false}）
 * 口径未校准——未重排时不得凭它过 cosine 闸（未经理裁决直接丢弃），经远程重排裁决后凭
 * {@code relevance >= rerankMinScore} 入上下文。过闸不足 {@code minCount} → 返回空（上层 RAG_SKIP）。
 */
class RetrievalValidatorTest {

    /** 余弦口径片段（稠密检索产出，可凭 score 过闸）。 */
    private static RagFragment cosineFrag(String text, double score) {
        return new RagFragment(text, score, "test", null, null, null, null, null, true);
    }

    /** BM25 口径片段（稀疏检索产出，未重排时不得凭 score 过闸）。 */
    private static RagFragment bm25Frag(String text, double score) {
        return new RagFragment(text, score, "test");
    }

    /** 被远程重排过的片段（relevance 裁决；score 保留检索置信度不被覆写）。 */
    private static RagFragment reranked(String text, double score, Double relevance) {
        return new RagFragment(text, score, "test", null, null, null, null, relevance, true);
    }

    private final RetrievalValidator validator = new RetrievalValidator(0.5, 2, 0.3);

    @Test
    void cosineFragments_filteredByThreshold() {
        List<RagFragment> in = List.of(cosineFrag("a", 0.9), cosineFrag("b", 0.4), cosineFrag("c", 0.6));
        List<RagFragment> out = validator.validate(in);
        assertThat(out).extracting(RagFragment::text).containsExactly("a", "c");
    }

    @Test
    void bm25OnlyFragments_droppedWithoutRerank() {
        // BM25-only（cosineScored=false）未经理裁决 → 不放过：词面命中 ≠ 语义置信
        List<RagFragment> in = List.of(bm25Frag("bm25-hit", 3.7), bm25Frag("bm25-hit2", 1.2));
        assertThat(validator.validate(in)).isEmpty();
    }

    @Test
    void rerankedFragment_judgedByRelevance_notCosine() {
        // 被远程重排过：relevance ≥ 0.3 过闸（cosine 0.2 的边际带候选由 cross-encoder 救回）
        RetrievalValidator minCountOne = new RetrievalValidator(0.5, 1, 0.3);
        List<RagFragment> in = List.of(reranked("rescued", 0.2, 0.85), reranked("junk", 0.9, 0.1));
        List<RagFragment> out = minCountOne.validate(in);
        assertThat(out).extracting(RagFragment::text).containsExactly("rescued");
    }

    @Test
    void rerankedFallbackBm25_keptByCosineWhenAboveThreshold() {
        // 远程重排失败→本地 BM25 兜底（只排序、relevance=null）→ 回退 cosine 判据：
        // 余弦口径片段按原 score 过闸，BM25-only 无口径被丢弃
        RetrievalValidator minCountOne = new RetrievalValidator(0.5, 1, 0.3);
        List<RagFragment> in = List.of(cosineFrag("dense-hit", 0.7), bm25Frag("bm25-only", 3.7));
        List<RagFragment> out = minCountOne.validate(in);
        assertThat(out).extracting(RagFragment::text).containsExactly("dense-hit");
    }

    @Test
    void belowMinCountReturnsEmpty() {
        // 仅 1 条过阈值，但 minCount=2 → 召回不足，整段跳过（触发 RAG_SKIP）
        List<RagFragment> in = List.of(cosineFrag("a", 0.9), cosineFrag("b", 0.1));
        assertThat(validator.validate(in)).isEmpty();
    }

    @Test
    void allPassReturnsUnchanged() {
        List<RagFragment> in = List.of(cosineFrag("a", 0.7), cosineFrag("b", 0.8));
        List<RagFragment> out = validator.validate(in);
        assertThat(out).extracting(RagFragment::text).containsExactly("a", "b");
    }

    @Test
    void nullOrEmptyInputReturnsEmpty() {
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate(List.of())).isEmpty();
    }

    @Test
    void decide_reportsPerFragmentVerdictAndReason() {
        // 观测口径：decide 与 validate 同源判定，逐片段给出过闸与否 + 淘汰原因（score/relevance vs 阈值）
        List<RetrievalValidator.GateRecord> records = validator.decide(List.of(
                cosineFrag("余弦达标", 0.7),
                cosineFrag("余弦不达标", 0.2),
                bm25Frag("bm25-only", 3.7),
                reranked("重排救回", 0.2, 0.85),
                reranked("重排淘汰", 0.9, 0.1)));

        assertThat(records).extracting(RetrievalValidator.GateRecord::passed)
                .containsExactly(true, false, false, true, false);
        assertThat(records.get(0).reason()).contains("余弦口径通过").contains("0.700");
        assertThat(records.get(1).reason()).contains("余弦低于阈值").contains("0.200").contains("0.50");
        assertThat(records.get(2).reason()).contains("无余弦口径").contains("BM25-only");
        assertThat(records.get(3).reason()).contains("重排裁决通过").contains("0.850");
        assertThat(records.get(4).reason()).contains("重排裁决淘汰").contains("0.100");
        // 同源不漂移：decide 的 passed 子集 == validate 的幸存集（minCount=2 恰好两条过闸）
        List<RagFragment> out = validator.validate(List.of(
                cosineFrag("余弦达标", 0.7),
                cosineFrag("余弦不达标", 0.2),
                bm25Frag("bm25-only", 3.7),
                reranked("重排救回", 0.2, 0.85),
                reranked("重排淘汰", 0.9, 0.1)));
        assertThat(out).extracting(RagFragment::text).containsExactly("余弦达标", "重排救回");
    }
}
