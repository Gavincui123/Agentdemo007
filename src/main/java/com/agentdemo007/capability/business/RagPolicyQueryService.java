package com.agentdemo007.capability.business;

import com.agentdemo007.capability.rag.RagFragment;
import com.agentdemo007.capability.rag.RetrievalValidator;
import com.agentdemo007.capability.rag.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 政策查询真 RAG 实现（单入口 seam 的真库实现·{@code vectorstore.type=chroma} 时装配）。
 *
 * <p>接住 [[business-tools-workflow-dag]] §2.1 决策 R 预留的演进：域映射（RETURN→received_return_policy /
 * REFUND→after_sale_policy / PROMOTION→promotion_and_member_policy）+ 以<b>用户问题原词</b>为检索词
 * 委托同一 {@link Retriever}（真库 {@code ChromaHybridRetriever} @Primary，宽召回融合）→
 * {@link RetrievalValidator} 置信度终闸<b>自动继承</b>（低置信知识绝不冒充政策答复）→
 * 域匹配优先取 top1（同域 FAQ 亦命中），无域匹配回退全局 top1，空 → FALLBACK 兜底话术（同 mock 口径）。
 *
 * <p>与 {@link MockPolicyQueryService} 属性门控互斥（{@code vectorstore.type}=chroma|inmemory）；
 * 换实现不换 seam/调用方（3 个政策 @Tool + DAG query_policy 节点无感知）。
 */
@Component
@ConditionalOnProperty(prefix = "vectorstore", name = "type", havingValue = "chroma")
public class RagPolicyQueryService implements PolicyQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagPolicyQueryService.class);

    /** PolicyDomain → 语料知识域（corpus metadata.domain，与 RoutePlanBaselines 口径一致）。 */
    private static final Map<PolicyDomain, String> KNOWLEDGE_DOMAINS = Map.of(
            PolicyDomain.RETURN, "received_return_policy",
            PolicyDomain.REFUND, "after_sale_policy",
            PolicyDomain.PROMOTION, "promotion_and_member_policy");

    /** 检索词缺省兜底（LLM 未透传 query 时按域级预置查询词检索，域内代表性总览片段）。 */
    private static final Map<PolicyDomain, String> DEFAULT_QUERIES = Map.of(
            PolicyDomain.RETURN, "退货政策",
            PolicyDomain.REFUND, "退款政策",
            PolicyDomain.PROMOTION, "会员与活动政策");

    /** 兜底 fragment（[[refusal-design]]）：hit=false=政策库无此条目 → ToolExecutionStep 路由 toolDataMisses，不冒充政策正文。 */
    private static final PolicyFragment FALLBACK = new PolicyFragment(
            "暂无相关政策信息，建议联系人工客服确认。", "兜底政策", false);

    private final Retriever retriever;
    private final RetrievalValidator validator;
    private final int recall;

    public RagPolicyQueryService(Retriever retriever,
                                 RetrievalValidator validator,
                                 @Value("${app.rag.recall-dense:24}") int recall) {
        this.retriever = retriever;
        this.validator = validator;
        this.recall = recall;
    }

    @Override
    public PolicyFragment query(PolicyDomain domain, String query) {
        if (domain == null) {
            return FALLBACK; // 幂等兜底不抛（§5.12 每步降级）
        }
        String effectiveQuery = (query != null && !query.isBlank())
                ? query : DEFAULT_QUERIES.get(domain);
        List<RagFragment> pool = retriever.retrieve(effectiveQuery, recall);
        List<RagFragment> gated = validator.validate(pool); // 置信度终闸继承（低置信不冒充政策）
        if (gated.isEmpty()) {
            // 工具通道观测：终闸全灭 → FALLBACK（与 RagStep 漏斗日志同风格，召回/过闸存量可见）
            log.debug("政策RAG走兜底：domain={} query='{}' 召回={}条 终闸过=0（低置信不冒充政策）",
                    domain, effectiveQuery, pool.size());
            return FALLBACK;
        }
        String expected = KNOWLEDGE_DOMAINS.get(domain);
        // 域匹配优先（保池序 = BM25 提权序），无域匹配回退全局 top1（跨域高置信亦可用）
        Optional<RagFragment> top = gated.stream()
                .filter(f -> expected.equals(f.domain()))
                .findFirst();
        RagFragment chosen = top.orElse(gated.get(0));
        log.debug("政策RAG检索：domain={} query='{}' 召回={} 终闸过={} 选择=[source={} score={} domain={}] {}",
                domain, effectiveQuery, pool.size(), gated.size(),
                chosen.source(), String.format("%.3f", chosen.score()), chosen.domain(),
                top.isPresent() ? "（域匹配top1）" : "（无域匹配回退全局top1）");
        return new PolicyFragment(chosen.displayText(), chosen.source());
    }
}
