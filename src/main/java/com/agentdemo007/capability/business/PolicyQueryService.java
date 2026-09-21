package com.agentdemo007.capability.business;

import com.agentdemo007.session.ChatSubject;

/**
 * 政策查询单入口 seam（[[business-tools-workflow-dag]] §2.1 决策 R·前期，后期单点切真 RAG）。
 *
 * <p>3 个政策 @Tool + DAG {@code query_policy} 节点全部委托此单 seam（各传自身 {@link PolicyDomain}）。
 * 真实 RAG 落地（已接）：{@code RagPolicyQueryService}（{@code vectorstore.type=chroma} 时装配）映射
 * domain→RoutePlan 知识域（RETURN→received_return_policy / REFUND→after_sale_policy /
 * PROMOTION→promotion_and_member_policy），以用户问题为检索词走同一 {@code Retriever} 漏斗
 * （粗滤/重排/置信度终闸全部继承）；mock 实现 inmemory 模式保留。schema 变更经用户批准（v5 计划）：
 * 增加 {@code query} 检索词入参——真 RAG 不拿用户问题检索基本无效，工具 schema 须让 LLM 透传问题原词。
 *
 * <p><b>Phase 21 等级门全通道覆盖</b>：真库实现按主体（{@link ChatSubject}）经 {@code KbCatalogService}
 * 目录快照过滤——匿名/V0 只出公开档，V1~V5 按 主体等级 ≥ 文档要求档 递升可见（与 RagStep ①′ 同谓词），
 * <b>政策工具通道不再绕过等级门</b>。2 参兼容口径 = 匿名主体（fail-closed：缺主体的调用方宁可少给
 * 不可越级）。mock 罐头数据无权限元数据，等级门不适用。
 */
public interface PolicyQueryService {

    /**
     * 按政策域查询，返政策正文 + citation 来源（RAG 通道拆 text+source）。
     *
     * @param domain  政策域（null → 幂等返兜底 fragment，不抛）
     * @param query   检索词（用户问题原词；空/blank → 实现用域级预置查询词兜底）
     * @param subject 请求主体（null → ANONYMOUS 收敛；真库实现据此做等级过滤）
     */
    PolicyFragment query(PolicyDomain domain, String query, ChatSubject subject);

    /** 既有 2 参兼容口径（主体=匿名，fail-closed V0 只出公开档；既有测试/调用方零改动）。 */
    default PolicyFragment query(PolicyDomain domain, String query) {
        return query(domain, query, ChatSubject.ANONYMOUS);
    }
}
