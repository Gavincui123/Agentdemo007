package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyQueryService;
import com.agentdemo007.session.ChatSubjectHolder;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

/**
 * 退货政策查询 @Tool（[[business-tools-workflow-dag]] §2.2·RAG 通道·决策 R 单入口 seam）。
 *
 * <p>委托 {@link PolicyQueryService#query(PolicyDomain, String, com.agentdemo007.session.ChatSubject)}
 * （真库 {@code RagPolicyQueryService} / inmemory mock 属性门控切换，不换 seam/调用方）；
 * {@code query} 参数 = 用户问题原词（LLM 透传，真 RAG 检索词——schema 扩展经用户批准 v5）。
 * 主体经 {@link ChatSubjectHolder} 请求窗口带入（ToolExecutionStep 写入/清除）——真库实现按等级过滤，
 * <b>政策工具通道不绕过等级门</b>。返回 {@code PolicyFragment.toJson()}
 * （{@code {"text","source"}}），路由进 {@code ragFragments}+{@code ragCitations}（带 citation）。
 * T2（RAG 政策）+ DAG query_policy 节点共用单 seam 政策源。
 */
@Component
public class ReturnPolicyTool {

    private final PolicyQueryService policyService;

    public ReturnPolicyTool(PolicyQueryService policyService) {
        this.policyService = policyService;
    }

    @Tool("查询退货政策：7天无理由退货规则、退货条件、退货窗口。query 必须传用户关于退货的问题原词")
    @ToolChannel(ToolCategory.RAG)
    public String queryReturnPolicy(String query) {
        return policyService.query(PolicyDomain.RETURN, query, ChatSubjectHolder.current()).toJson();
    }
}
