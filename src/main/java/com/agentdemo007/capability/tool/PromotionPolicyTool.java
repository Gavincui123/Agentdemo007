package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 活动政策查询 @Tool（[[business-tools-workflow-dag]] §2.2·RAG 通道·决策 Q 补齐 + 决策 R 单入口 seam）。
 *
 * <p>委托 {@link PolicyQueryService#query(PolicyDomain)}（mock，后期单点切真 RAG）；
 * 返回 {@code PolicyFragment.toJson()}，路由进 {@code ragFragments}+{@code ragCitations}（带 citation）。
 * T3 商品推荐（活动/会员/满减政策）共用单 seam 政策源。
 */
@Component
public class PromotionPolicyTool {

    private final PolicyQueryService policyService;

    public PromotionPolicyTool(PolicyQueryService policyService) {
        this.policyService = policyService;
    }

    @Tool("查询活动政策：会员活动、满减规则、会员价与活动叠加规则")
    @ToolChannel(ToolCategory.RAG)
    public String queryPromotionPolicy() {
        return policyService.query(PolicyDomain.PROMOTION).toJson();
    }
}
