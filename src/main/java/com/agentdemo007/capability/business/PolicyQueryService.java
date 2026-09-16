package com.agentdemo007.capability.business;

/**
 * 政策查询单入口 seam（[[business-tools-workflow-dag]] §2.1 决策 R·前期，后期单点切真 RAG）。
 *
 * <p>3 个政策 @Tool + DAG {@code query_policy} 节点全部委托此单 seam（各传自身 {@link PolicyDomain}）。
 * 后期接真 RAG：加 {@code RagPolicyQueryService implements PolicyQueryService}，{@code query(domain)}
 * 映射 domain→RoutePlan 知识域（RETURN→received_return_policy / REFUND→after_sale_policy /
 * PROMOTION→promotion_and_member_policy）委托既有 {@code HybridRetriever}（Phase 20/21）。
 * 换实现不换 seam/调用方/工具 schema——单入口改造点（用户钦定"政策类后续改造为单入口接入真实 RAG，目前先做好前期"）。
 */
public interface PolicyQueryService {

    /** 按政策域查询，返政策正文 + citation 来源（RAG 通道拆 text+source）。 */
    PolicyFragment query(PolicyDomain domain);
}
