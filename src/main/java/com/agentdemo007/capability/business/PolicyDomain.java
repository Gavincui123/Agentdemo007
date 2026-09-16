package com.agentdemo007.capability.business;

/**
 * 政策知识域（政策单入口 seam·[[business-tools-workflow-dag]] §2.1 决策 R）。
 *
 * <p>3 政策 @Tool（Return/Refund/PromotionPolicyTool）+ DAG query_policy 节点各传自身 domain
 * 委托 {@link PolicyQueryService} 单 seam。后期接真 RAG 时 domain 映射 RoutePlan 知识域：
 * RETURN→received_return_policy / REFUND→after_sale_policy / PROMOTION→promotion_and_member_policy。
 */
public enum PolicyDomain {
    RETURN,
    REFUND,
    PROMOTION
}
