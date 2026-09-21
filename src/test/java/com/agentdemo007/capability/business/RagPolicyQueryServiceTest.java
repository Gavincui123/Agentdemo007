package com.agentdemo007.capability.business;

import com.agentdemo007.capability.kb.KbCatalogService;
import com.agentdemo007.capability.kb.KbLevel;
import com.agentdemo007.capability.kb.KbNamespace;
import com.agentdemo007.capability.kb.KbSourceRef;
import com.agentdemo007.capability.rag.RetrievalValidator;
import com.agentdemo007.capability.rag.Retriever;
import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.persistence.entity.KbDocumentEntity;
import com.agentdemo007.persistence.repository.KbDocumentRepository;
import com.agentdemo007.session.ChatSubject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
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
 * ⑤ null domain 幂等兜底；⑥ Phase 21 等级门（政策工具通道不绕行）：V1 查 V3 托管政策不可见、
 * top1 从幸存者中选、匿名 fail-closed、滤空 FALLBACK 零泄漏。
 */
class RagPolicyQueryServiceTest {

    private final KbDocumentRepository repository = mock(KbDocumentRepository.class);
    private final KbCatalogService catalog = new KbCatalogService(repository);

    private static RagFragment frag(String text, String domain, double score) {
        return new RagFragment(text, score, "01-refund-policy.md", null, null, null, domain, null, true);
    }

    private RagPolicyQueryService newService(Retriever retriever) {
        return new RagPolicyQueryService(retriever, new RetrievalValidator(0.3, 1, 0.3), catalog, 24);
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

    // ---- Phase 21 等级门（政策工具通道不绕行）----

    /** 托管 KB 政策片段（kb: source + 余弦口径过终闸 + after_sale_policy 域）。 */
    private static RagFragment managedFrag(String docNo, String text, double score) {
        String docUid = KbSourceRef.docUid(KbNamespace.PUBLIC, docNo);
        return new RagFragment(text, score, KbSourceRef.source(docUid, 1, 1),
                null, null, null, "after_sale_policy", null, true);
    }

    private static KbDocumentEntity publicDoc(String docNo, KbLevel level) {
        return KbDocumentEntity.create(KbNamespace.PUBLIC, docNo, null, level, "标题", "faq.md", "md",
                null, 1, "checksum", 1, 10, null, "admin", Instant.now());
    }

    private void seedDocs(KbDocumentEntity... docs) {
        when(repository.findByStatusOrderByCreatedAtDesc(KbDocumentEntity.Status.ACTIVE)).thenReturn(List.of(docs));
        Arrays.stream(docs).forEach(catalog::refresh);
    }

    @Test
    void memberLevelGatesManagedPolicyFragments() {
        seedDocs(publicDoc("basic-policy", KbLevel.V0), publicDoc("gold-policy", KbLevel.V3));
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt())).thenReturn(List.of(
                managedFrag("gold-policy", "黄金会员专属：当日极速退款。", 0.9),
                managedFrag("basic-policy", "基础退款规则：3-7 个工作日到账。", 0.6)));
        RagPolicyQueryService service = newService(retriever);

        // V1 查 V3 不可见：等级过滤在 top1 选择前——幸存者中选基础档，非"选中后丢弃"
        PolicyFragment v1 = service.query(PolicyDomain.REFUND, "退款多久到账",
                ChatSubject.of("10010", KbLevel.V1));
        assertThat(v1.text()).contains("基础退款规则");
        assertThat(v1.source()).doesNotContain("gold-policy");

        // V5 全量可见：域匹配 top1 = 池首 gold
        PolicyFragment v5 = service.query(PolicyDomain.REFUND, "退款多久到账",
                ChatSubject.of("10086", KbLevel.V5));
        assertThat(v5.text()).contains("黄金会员专属");

        // 匿名 fail-closed V0：只见公开档
        PolicyFragment anon = service.query(PolicyDomain.REFUND, "退款多久到账", ChatSubject.ANONYMOUS);
        assertThat(anon.text()).contains("基础退款规则");
    }

    @Test
    void onlyGatedDocument_anonymousFallsBackWithZeroLeak() {
        seedDocs(publicDoc("gold-policy", KbLevel.V3));
        Retriever retriever = mock(Retriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(managedFrag("gold-policy", "黄金会员专属：当日极速退款。", 0.9)));
        RagPolicyQueryService service = newService(retriever);

        PolicyFragment out = service.query(PolicyDomain.REFUND, "退款多久到账", null); // null subject 亦收敛

        assertThat(out.text()).contains("暂无相关政策信息"); // 滤空 → FALLBACK，零泄漏
        assertThat(out.source()).isEqualTo("兜底政策");
    }
}
