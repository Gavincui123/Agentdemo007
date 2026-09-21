package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyQueryService;
import com.agentdemo007.session.ChatSubjectHolder;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 活动政策查询 @Tool（[[business-tools-workflow-dag]] §2.2·RAG 通道·决策 Q 补齐 + 决策 R 单入口 seam）。
 *
 * <p>委托 {@link PolicyQueryService#query(PolicyDomain, String, com.agentdemo007.session.ChatSubject)}
 * （真库/inmemory 门控切换，不换 seam/调用方）；{@code query} 参数 = 用户问题原词（真 RAG 检索词，
 * schema 扩展经用户批准 v5）。主体经 {@link ChatSubjectHolder} 请求窗口带入（ToolExecutionStep 写入/
 * 清除）——真库实现按等级过滤，<b>政策工具通道不绕过等级门</b>。
 * 返回 {@code PolicyFragment.toJson()}，路由进 {@code ragFragments}+{@code ragCitations}（带 citation）。
 * T3 商品推荐（活动/会员/满减政策）共用单 seam 政策源。
 */
@Component
public class PromotionPolicyTool {

    private final PolicyQueryService policyService;

    public PromotionPolicyTool(PolicyQueryService policyService) {
        this.policyService = policyService;
    }

    @Tool("查询活动政策：会员活动、满减规则、会员价与活动叠加规则。query 必须传用户关于活动/会员的问题原词")
    @ToolChannel(ToolCategory.RAG)
    public String queryPromotionPolicy(String query) {
        return policyService.query(PolicyDomain.PROMOTION, query, ChatSubjectHolder.current()).toJson();
    }
}
