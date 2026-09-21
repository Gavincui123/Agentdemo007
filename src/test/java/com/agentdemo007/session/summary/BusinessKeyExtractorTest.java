package com.agentdemo007.session.summary;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务键提取器测试（Phase 22·T101 摘要白名单 + T102 画像守门共用词法）。
 */
class BusinessKeyExtractorTest {

    @Test
    void extract_findsOrderTicketRefundIds() {
        assertThat(BusinessKeyExtractor.extract("订单 ORD-001 已退款，工单 HITL-12 处理中，退款单 RF-2024"))
                .containsExactly("ORD-001", "HITL-12", "RF-2024");
    }

    @Test
    void extract_deduplicatesKeepingOrder() {
        assertThat(BusinessKeyExtractor.extract("ORD-001 又提 ORD-001"))
                .containsExactly("ORD-001");
    }

    @Test
    void extract_ignoresPlainNumbersAndLowercase() {
        // 纯数字（手机号/金额）与全小写不误伤——长数字单号不纳入（守门避免误杀手机号场景）
        assertThat(BusinessKeyExtractor.extract("手机 13800138000，金额 199.00，单号 ord-1"))
                .isEmpty();
    }

    @Test
    void extract_nullOrBlank_returnsEmpty() {
        assertThat(BusinessKeyExtractor.extract(null)).isEmpty();
        assertThat(BusinessKeyExtractor.extract("  ")).isEmpty();
    }

    @Test
    void extractAll_unionsAcrossTexts() {
        assertThat(BusinessKeyExtractor.extractAll(java.util.Arrays.asList("ORD-001 退款", null, "另一单 ORD-002")))
                .containsExactly("ORD-001", "ORD-002");
    }

    @Test
    void contains_quickCheck() {
        assertThat(BusinessKeyExtractor.contains("查一下 ORD-003")).isTrue();
        assertThat(BusinessKeyExtractor.contains("没有单号")).isFalse();
        assertThat(BusinessKeyExtractor.contains(null)).isFalse();
    }

    @Test
    void extract_setTypeIsLinkedHashSet_semanticUniqueness() {
        Set<String> keys = BusinessKeyExtractor.extract("RF-1 RF-2");
        assertThat(keys).hasSize(2);
    }
}
