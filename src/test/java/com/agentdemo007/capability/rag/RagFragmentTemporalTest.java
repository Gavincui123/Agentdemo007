package com.agentdemo007.capability.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RagFragment} 时效字段 + 展示文本测评（Phase 20·T90）。
 *
 * <p>片段增 {@code timestamp}/{@code validUntil}/{@code temporalTag} 强类型字段（④收口，非 Map）。
 * {@link RagFragment#displayText()} 在 RAG→文本抽取边界（RagStep）按时效标注：
 * <ul>
 *   <li>历史片段（{@code temporalTag=HISTORICAL}）→ 前缀"【历史参考资料·截至{date}】"隔离标注，
 *       过时不冒充当前（§5.4.1 时效治理）；</li>
 *   <li>当前/无标注 → 原文（不污染）。</li>
 * </ul>
 * 3 参构造保留（既有调用方零改动），temporal 字段缺省 null。
 */
class RagFragmentTemporalTest {

    @Test
    void historicalFragment_displayTextPrefixedWithHistoricalLabel() {
        Instant validUntil = Instant.parse("2024-06-30T00:00:00Z");
        RagFragment f = new RagFragment("旧退款政策", 0.5, "doc1", null, validUntil, "HISTORICAL");

        String display = f.displayText();

        assertThat(display).startsWith("【历史参考资料·截至2024-06-30】");
        assertThat(display).contains("旧退款政策");
    }

    @Test
    void historicalFragment_noValidUntil_usesTimestampAsLabel() {
        Instant timestamp = Instant.parse("2024-03-15T00:00:00Z");
        RagFragment f = new RagFragment("旧政策", 0.5, "doc1", timestamp, null, "HISTORICAL");

        assertThat(f.displayText()).startsWith("【历史参考资料·截至2024-03-15】").contains("旧政策");
    }

    @Test
    void historicalFragment_noDates_labelWithoutDate() {
        RagFragment f = new RagFragment("旧政策", 0.5, "doc1", null, null, "HISTORICAL");

        assertThat(f.displayText()).startsWith("【历史参考资料】").contains("旧政策");
        assertThat(f.displayText()).doesNotContain("截至");
    }

    @Test
    void currentFragment_displayTextIsPlainText() {
        RagFragment f = new RagFragment("退款流程", 0.5, "doc1",
                Instant.parse("2024-06-30T00:00:00Z"), null, "CURRENT");

        assertThat(f.displayText()).isEqualTo("退款流程");
    }

    @Test
    void noTemporalTag_displayTextIsPlainText() {
        RagFragment f = new RagFragment("退款流程", 0.5, "doc1"); // 3 参 → temporal null

        assertThat(f.displayText()).isEqualTo("退款流程");
        assertThat(f.timestamp()).isNull();
        assertThat(f.validUntil()).isNull();
        assertThat(f.temporalTag()).isNull();
    }

    @Test
    void temporalFields_roundTripPreserved() {
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        Instant vu = Instant.parse("2024-12-31T00:00:00Z");
        RagFragment f = new RagFragment("片段", 0.9, "src", ts, vu, "HISTORICAL");

        assertThat(f.timestamp()).isEqualTo(ts);
        assertThat(f.validUntil()).isEqualTo(vu);
        assertThat(f.temporalTag()).isEqualTo("HISTORICAL");
    }
}
