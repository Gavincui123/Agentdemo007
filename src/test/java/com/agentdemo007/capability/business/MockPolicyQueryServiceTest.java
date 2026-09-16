package com.agentdemo007.capability.business;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 政策单入口 seam 测试（Slice 1·前期，[[business-tools-workflow-dag]] 决策 R）。
 *
 * <p>3 个政策 @Tool + DAG query_policy 节点均委托此 seam；mock 实现按 domain 返 canned 政策文本+citation。
 * 后期换 {@code RagPolicyQueryService}（委托 HybridRetriever）不换 seam/调用方/工具 schema——单点切真 RAG。
 *
 * <p>覆盖：每个 domain 返非空 text+source 且可区分；null domain 幂等返兜底不抛（②每步降级，不阻塞）。
 */
class MockPolicyQueryServiceTest {

    private final MockPolicyQueryService service = new MockPolicyQueryService();

    @Test
    void queryReturn_returnPolicyTextAndCitation() {
        PolicyFragment fragment = service.query(PolicyDomain.RETURN);

        assertThat(fragment).isNotNull();
        assertThat(fragment.text()).isNotBlank();
        assertThat(fragment.text()).contains("7天");
        assertThat(fragment.source()).isNotBlank();
        assertThat(fragment.source()).contains("退货");
    }

    @Test
    void queryRefund_refundPolicyTextAndCitation() {
        PolicyFragment fragment = service.query(PolicyDomain.REFUND);

        assertThat(fragment).isNotNull();
        assertThat(fragment.text()).isNotBlank();
        assertThat(fragment.source()).isNotBlank();
        assertThat(fragment.source()).contains("退款");
    }

    @Test
    void queryPromotion_promotionPolicyTextAndCitation() {
        PolicyFragment fragment = service.query(PolicyDomain.PROMOTION);

        assertThat(fragment).isNotNull();
        assertThat(fragment.text()).isNotBlank();
        assertThat(fragment.source()).isNotBlank();
        assertThat(fragment.source()).contains("活动");
    }

    @Test
    void queryNull_fallbackNoThrow() {
        // null domain 幂等兜底（不抛异常，不阻塞主链路，②每步降级）
        PolicyFragment fragment = service.query(null);

        assertThat(fragment).isNotNull();
    }

    @Test
    void queryEachDomain_distinctSources() {
        String returnSrc = service.query(PolicyDomain.RETURN).source();
        String refundSrc = service.query(PolicyDomain.REFUND).source();
        String promoSrc = service.query(PolicyDomain.PROMOTION).source();

        assertThat(returnSrc).isNotEqualTo(refundSrc);
        assertThat(returnSrc).isNotEqualTo(promoSrc);
        assertThat(refundSrc).isNotEqualTo(promoSrc);
    }
}
