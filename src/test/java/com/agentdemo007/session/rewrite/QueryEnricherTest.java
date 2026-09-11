package com.agentdemo007.session.rewrite;

import com.agentdemo007.session.model.QueryEnrichment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询补全器测评（Phase 20·T88）。
 *
 * <p>{@link QueryEnricher#enrich} 从用户查询**抽取**精确词（订单号/型号/发票类型）与时间线——
 * 只补全不替换、只抽取事实**不下业务结论**（不判定订单状态/意图归属/业务决策），产出
 * {@link QueryEnrichment} 强类型槽（关键词 + 时间线），供 Hybrid RAG 关键词/规则路由通道消费
 * （T89：精确词走精确通道不走纯语义）。原查询经 {@code rawInput()} 保留（不在此覆盖）。
 *
 * <p>dev 确定性抽取（正则 ASCII 标识符 + CJK 词典精确词 + 时间线正则），prod 覆盖为 NER/分词。
 */
class QueryEnricherTest {

    private final QueryEnricher enricher = new QueryEnricher();

    @Test
    void extractsOrderIdAndTimeline() {
        QueryEnrichment enr = enricher.enrich("查一下订单 ORD123456 在 Q3 的状态");

        assertThat(enr.keywords()).containsExactly("ORD123456");
        assertThat(enr.timeline()).isEqualTo("Q3");
    }

    @Test
    void pureDigitOrderId_extractedAsKeyword() {
        QueryEnrichment enr = enricher.enrich("订单 12345678 详情");

        assertThat(enr.keywords()).containsExactly("12345678");
        assertThat(enr.timeline()).isNull();
    }

    @Test
    void cjkDictionaryTerm_invoiceType_extracted() {
        QueryEnrichment enr = enricher.enrich("帮我开增值税专用发票");

        assertThat(enr.keywords()).containsExactly("增值税专用发票");
        assertThat(enr.timeline()).isNull();
    }

    @Test
    void yearWithSuffix_capturedAsTimeline_notKeyword() {
        // 4 位年份不作为订单号关键词（避免误把年份当 ID），仅时间线
        QueryEnrichment enr = enricher.enrich("2024年销售数据");

        assertThat(enr.keywords()).isEmpty();
        assertThat(enr.timeline()).isEqualTo("2024年");
    }

    @Test
    void noPreciseTerms_returnsEmpty() {
        QueryEnrichment enr = enricher.enrich("退款流程");

        assertThat(enr.keywords()).isEmpty();
        assertThat(enr.timeline()).isNull();
    }

    @Test
    void blankQuery_returnsEmpty() {
        assertThat(enricher.enrich("")).isEqualTo(QueryEnrichment.EMPTY);
        assertThat(enricher.enrich(null)).isEqualTo(QueryEnrichment.EMPTY);
    }

    @Test
    void multipleKeywords_preserveFirstOccurrenceOrder_dedup() {
        QueryEnrichment enr = enricher.enrich("订单 ABC12345 与 SO20240001 开电子发票");

        assertThat(enr.keywords()).containsExactly("ABC12345", "SO20240001", "电子发票");
    }

    /**
     * code-review #5：金额（满…元 / 金额…）非订单号/型号——纯数字 token 在金额上下文时
     * 不应作精确词（会误命中含该数字串的无关片段）。纯数字订单号（无金额上下文）仍抽取
     * （见 {@link #pureDigitOrderId_extractedAsKeyword}）——只排金额，不排标识符。
     */
    @Test
    void amountInCurrencyContext_notExtractedAsKeyword_pureDigitIdStillExtracted() {
        // 金额：前接"满"+后接"元" → 非关键词
        QueryEnrichment amount = enricher.enrich("消费满10000元可享折扣");
        assertThat(amount.keywords()).doesNotContain("10000");

        // 纯数字订单号（无金额上下文）仍抽取——确认金额排除不误伤标识符
        QueryEnrichment id = enricher.enrich("订单 12345678 详情");
        assertThat(id.keywords()).contains("12345678");
    }

    /**
     * code-review #6：dev 关键词通道原大小写敏感（contains + 哈希 tokenize 均不转小写），
     * 小写订单号零召回。ASCII 关键词须规范化大写以匹配大写种子；含字母标识符统一大写，
     * 纯数字标识符大写无变化（12345678→12345678）。
     */
    @Test
    void lowercaseAsciiId_normalizedToUppercase_keyword() {
        QueryEnrichment enr = enricher.enrich("查订单 ord123456");

        assertThat(enr.keywords()).contains("ORD123456");
    }
}
