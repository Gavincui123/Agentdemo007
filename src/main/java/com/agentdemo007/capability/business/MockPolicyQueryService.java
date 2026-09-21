package com.agentdemo007.capability.business;

import com.agentdemo007.session.ChatSubject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 政策查询 mock 实现（单入口 seam 的 mock 桩·[[business-tools-workflow-dag]] §2.1 决策 R 前期）。
 *
 * <p>按 {@link PolicyDomain} 返 canned 政策文本 + citation（"退货/退款/活动政策知识库§x"）。
 * 真库模式由 {@code RagPolicyQueryService}（委托 {@code Retriever} 真实 RAG 漏斗）替换——
 * 换 impl 不换 seam/调用方；两者以 {@code vectorstore.type} 属性门控互斥（inmemory 默认 mock）。
 *
 * <p>收口：null domain / query 幂等返兜底 fragment 不抛（不阻塞主链路，§5.12 每步降级）；
 * {@code query} 检索词在 mock 语义下无消费方（canned 域级文本），签名随 seam 扩展保留。
 */
@Component
@ConditionalOnProperty(prefix = "vectorstore", name = "type", havingValue = "inmemory", matchIfMissing = true)
public class MockPolicyQueryService implements PolicyQueryService {

    private static final PolicyFragment RETURN_POLICY = new PolicyFragment(
            "7天无理由退货：签收后7天内可申请退货，商品须完好未使用。超7天仅支持质量问题退货。",
            "退货政策知识库§3");
    private static final PolicyFragment REFUND_POLICY = new PolicyFragment(
            "退款到账时效：原支付渠道3-7个工作日；质量问题全额退款，无理由退货扣运费。",
            "退款政策知识库§2");
    private static final PolicyFragment PROMOTION_POLICY = new PolicyFragment(
            "会员活动：gold级会员享会员价与满减叠加，活动商品不参与无理由退货。",
            "活动政策知识库§1");
    /**
     * 兜底 fragment（[[refusal-design]]）：hit=false 标记"政策库无此条目"——经 @Tool JSON 透传后，
     * {@code ToolExecutionStep} 据此路由进 {@code toolDataMisses}（工具无数据通道），
     * 不再混入 ragFragments 冒充政策正文由 LLM 自由转述。
     */
    private static final PolicyFragment FALLBACK = new PolicyFragment(
            "暂无相关政策信息，建议联系人工客服确认。",
            "兜底政策", false);

    @Override
    public PolicyFragment query(PolicyDomain domain, String query, ChatSubject subject) {
        // 罐头数据无权限元数据——等级门不适用（真库 RagPolicyQueryService 按目录快照过滤，seam javadoc）
        if (domain == null) {
            return FALLBACK;
        }
        return switch (domain) {
            case RETURN -> RETURN_POLICY;
            case REFUND -> REFUND_POLICY;
            case PROMOTION -> PROMOTION_POLICY;
        };
    }
}
