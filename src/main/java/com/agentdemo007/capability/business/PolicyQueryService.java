package com.agentdemo007.capability.business;

/**
 * 政策查询单入口 seam（[[business-tools-workflow-dag]] §2.1 决策 R·前期，后期单点切真 RAG）。
 *
 * <p>3 个政策 @Tool + DAG {@code query_policy} 节点全部委托此单 seam（各传自身 {@link PolicyDomain}）。
 * 真实 RAG 落地（已接）：{@code RagPolicyQueryService}（{@code vectorstore.type=chroma} 时装配）映射
 * domain→RoutePlan 知识域（RETURN→received_return_policy / REFUND→after_sale_policy /
 * PROMOTION→promotion_and_member_policy），以用户问题为检索词走同一 {@code Retriever} 漏斗
 * （粗滤/重排/置信度终闸全部继承）；mock 实现 inmemory 模式保留。schema 变更经用户批准（v5 计划）：
 * 增加 {@code query} 检索词入参——真 RAG 不拿用户问题检索基本无效，工具 schema 须让 LLM 透传问题原词。
 */
public interface PolicyQueryService {

    /**
     * 按政策域查询，返政策正文 + citation 来源（RAG 通道拆 text+source）。
     *
     * @param domain 政策域（null → 幂等返兜底 fragment，不抛）
     * @param query  检索词（用户问题原词；空/blank → 实现用域级预置查询词兜底）
     */
    PolicyFragment query(PolicyDomain domain, String query);
}
