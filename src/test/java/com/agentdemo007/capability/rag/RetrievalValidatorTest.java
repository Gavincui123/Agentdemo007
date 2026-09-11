package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索校验器测试（第四层·相关性阈值 + 数量下限）。
 *
 * <p>过滤低于分数阈值的片段；若存活数量不足 {@code minCount}，视为召回不可靠 → 返回空
 * （触发 RAG 跳过 {@code Degrade(RAG_SKIP)}，§5.12 RAG 行、deg-004）。
 */
class RetrievalValidatorTest {

    private static RagFragment frag(String text, double score) {
        return new RagFragment(text, score, "test");
    }

    private final RetrievalValidator validator = new RetrievalValidator(0.5, 2);

    @Test
    void filtersBelowThreshold() {
        List<RagFragment> in = List.of(frag("a", 0.9), frag("b", 0.4), frag("c", 0.6));
        List<RagFragment> out = validator.validate(in);
        assertThat(out).extracting(RagFragment::text).containsExactly("a", "c");
    }

    @Test
    void belowMinCountReturnsEmpty() {
        // 仅 1 条过阈值，但 minCount=2 → 召回不足，整段跳过
        List<RagFragment> in = List.of(frag("a", 0.9), frag("b", 0.1));
        assertThat(validator.validate(in)).isEmpty();
    }

    @Test
    void allPassReturnsUnchanged() {
        List<RagFragment> in = List.of(frag("a", 0.7), frag("b", 0.8));
        List<RagFragment> out = validator.validate(in);
        assertThat(out).extracting(RagFragment::text).containsExactly("a", "b");
    }

    @Test
    void nullInputReturnsEmpty() {
        assertThat(validator.validate(null)).isEmpty();
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(validator.validate(List.of())).isEmpty();
    }
}
