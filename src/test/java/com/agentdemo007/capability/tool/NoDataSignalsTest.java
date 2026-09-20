package com.agentdemo007.capability.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无数据信号识别单测（[[refusal-design]]）：现有 @Tool 无数据文案口径必须全部命中，
 * 正常数据/错误 JSON/空内容不得误判。
 */
class NoDataSignalsTest {

    @Test
    void matchesExistingToolMissPhrases() {
        assertThat(NoDataSignals.isNoData("订单 ORD-999 不存在")).isTrue();
        assertThat(NoDataSignals.isNoData("商品 SKU-404 不存在")).isTrue();
        assertThat(NoDataSignals.isNoData("无可售商品匹配「耳机」")).isTrue();
        assertThat(NoDataSignals.isNoData("当前无可售商品")).isTrue();
        assertThat(NoDataSignals.isNoData("未查询到该用户的会员记录")).isTrue();
        assertThat(NoDataSignals.isNoData("暂无相关政策信息，建议联系人工客服确认。")).isTrue();
    }

    @Test
    void doesNotMatchRealDataOrErrors() {
        assertThat(NoDataSignals.isNoData("订单 ORD-001：用户 10086，状态已发货，金额 199.00")).isFalse();
        assertThat(NoDataSignals.isNoData("可售商品：\n商品 SKU-001：无线耳机")).isFalse();
        assertThat(NoDataSignals.isNoData("{\"error\":\"timeout\",\"category\":\"RUNTIME\"}")).isFalse();
        assertThat(NoDataSignals.isNoData(null)).isFalse();
        assertThat(NoDataSignals.isNoData("  ")).isFalse();
    }
}
