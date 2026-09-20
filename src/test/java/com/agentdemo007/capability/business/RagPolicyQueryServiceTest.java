package com.agentdemo007.capability.business;

import com.agentdemo007.capability.rag.RetrievalValidator;
import com.agentdemo007.capability.rag.Retriever;
import com.agentdemo007.capability.rag.RagFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RagPolicyQueryService} 单测（真库政策单入口：域映射 + 置信度终闸继承 + 域匹配优先）。
 *
 * <p>断言：① 域映射（RETURN→received_return_policy / REFUND→after_sale_policy /
 * PROMOTION→promotion_and_member_policy）；② 域匹配片段优先、无域匹配回退全局 top1；
 * ③ 终闸不达标 → FALLBACK 兜底话术（低置信知识绝不冒充政策答复）；④ query 空 → 域级预置查询词；
 * ⑤ null domain 幂等兜底。
 */
class RagPolicyQueryServiceTest {

    private static RagFragment frag(String text, String domain, double score) {
        return new RagFragment(text, score, "01-refund-policy.md", null, null, null, domain, null, true);
    }

    private RagPolicyQueryService newService(Retriever retriever) {
        return new RagPolicyQueryService(retriever, new RetrievalValidator(0.3, 1, 0.3), 24);
    }

    @Test
    void domainMatchedFragment_preferred() {
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                frag("faq:退货时效问答", "faq", 0.9),                       // BM25 提权位但域不同
                frag("退货政策：7天无理由", "received_return_policy", 0.6),  // 域匹配
                frag("退款政策", "after_sale_policy", 0.5)));
        RagPolicyQueryService service = newService(retriever);

        PolicyFragment out = service.query(PolicyDomain.RETURN, "退货政策是什么");

        assertThat(out.text()).contains("7天无理由");          // 域匹配优先（非池首 faq 片段）
        assertThat(out.source()).isEqualTo("01-refund-policy.md");
    }

    @Test
    void noDomainMatch_fallsBackToGlobalTop1() {
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                frag("faq:跨域高置信片段", "faq", 0.8)));
        RagPolicyQueryService service = newService(retriever);

        PolicyFragment out = service.query(PolicyDomain.REFUND, "退款多久到账");

        assertThat(out.text()).contains("跨域高置信"); // 无域匹配 → 回退全局 top1
    }

    @Test
    void confidenceGateNotMet_fallbackPhrase() {
        Retriever retriever = mock(Retriever.class);
        // BM25-only 无余弦口径（cosineScored=false）→ 未经理裁决不放过 → FALLBACK
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new RagFragment("bm25-only", 3.7, "x.md")));
        RagPolicyQueryService service = newService(retriever);

        PolicyFragment out = service.query(PolicyDomain.REFUND, "退款多久到账");

        assertThat(out.text()).contains("暂无相关政策信息"); // 低置信知识绝不冒充政策
        assertThat(out.source()).isEqualTo("兜底政策");
    }

    @Test
    void blankQuery_usesDomainDefaultQuery() {
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                frag("退款政策总览", "after_sale_policy", 0.7)));
        RagPolicyQueryService service = newService(retriever);

        PolicyFragment out = service.query(PolicyDomain.REFUND, "  "); // blank → 域级预置查询词

        assertThat(out.text()).contains("退款政策总览");
        org.mockito.Mockito.verify(retriever).retrieve("退款政策", 24); // 域级预置查询词 + recall 预算
    }

    @Test
    void nullDomain_fallbackNoThrow() {
        RagPolicyQueryService service = newService(mock(Retriever.class));

        assertThat(service.query(null, null).text()).contains("暂无相关政策信息");
    }
}
