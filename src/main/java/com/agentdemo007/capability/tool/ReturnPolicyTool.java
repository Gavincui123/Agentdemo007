package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyQueryService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 退货政策查询 @Tool（[[business-tools-workflow-dag]] §2.2·RAG 通道·决策 R 单入口 seam）。
 *
 * <p>委托 {@link PolicyQueryService#query(PolicyDomain)}（mock 实现，后期单点切真 RAG 不换 seam/调用方/schema）；
 * 返回 {@code PolicyFragment.toJson()}（{@code {"text","source"}}），路由进 {@code ragFragments}+{@code ragCitations}
 * （带 citation）。T2（RAG 政策）+ DAG query_policy 节点共用单 seam 政策源。
 */
@Component
public class ReturnPolicyTool {

    private final PolicyQueryService policyService;

    public ReturnPolicyTool(PolicyQueryService policyService) {
        this.policyService = policyService;
    }

    @Tool("查询退货政策：7天无理由退货规则、退货条件、退货窗口")
    @ToolChannel(ToolCategory.RAG)
    public String queryReturnPolicy() {
        return policyService.query(PolicyDomain.RETURN).toJson();
    }
}
