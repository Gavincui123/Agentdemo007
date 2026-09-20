package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.OrderQueryService;
import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyQueryService;
import com.agentdemo007.capability.business.UserQueryService;
import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.gateway.llm.ChatLlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 高风险固定工作流条件装配（[[business-tools-workflow-dag]] §2.3·AfterSaleWorkflowGraph 参数化图）。
 *
 * <p>镜像 {@link com.agentdemo007.config.LangGraphConfig} 条件装配模式：{@code app.workflow.enabled=true}
 * 时装配退货/退款<b>两</b>个 {@link AfterSaleWorkflowGraph}（D1 参数化：共享 typed 服务 + 裁决/提交 seam，
 * 政策域区分流）。<b>提交制定案（2026-09-20 用户裁决：审批是事件、Agent 最小权限）</b>：
 * <ul>
 *   <li><b>审批 = 建工单即返回</b>（{@link TicketApprovalSubmitter}）：PENDING 人工工单（管理台可见，
 *       {@code wfa:{action}:{orderId}} 幂等键，业务门对账照常）建单后<b>立即返回，无请求内等待</b>——
 *       批准/驳回是管理台的工单状态事件，超时不可能影响决议（等待窗口/桥唤醒机制整体退役）；
 *       批准事件由 {@link AfterSaleBusinessExecutor}（业务系统 mock）消费留痕；</li>
 *   <li><b>资格判定 = Agent 裁决</b>（{@link LlmAfterSaleEligibilityJudge}）：政策知识 + 订单实时事实
 *       一并交模型三态裁决——<b>硬编码窗口规则退役</b>（LLM 缺席/失败→UNCERTAIN fail-safe 到人工）；</li>
 *   <li>客户对结果的感知 = 工单状态查询工具（{@code TicketStatusQueryTool}）读工单进度。</li>
 * </ul>
 * 缺省（enabled 缺省 false）不装配本类→零工作流 bean→{@code WorkflowExecutionStep@670} 亦不装配
 * （同属性门控）→旧链线性等价不变。
 *
 * <p><b>return/refund 流选择</b>：两 bean 同为 {@link AfterSaleWorkflow}，{@link WorkflowExecutionStep}
 * 按 {@code rp.intent()} 选（return_request→退货图 / refund_request→退款图），故两 bean 须用
 * {@code @Bean(name=...)} 显式命名 + 调用方 {@code @Qualifier} 注入（同类型双 bean 须消歧）。
 *
 * <p>已知限制（mock 期）：{@code currentUserId} 固定 10086——真接入后由 auth/session per-request 注入。
 */
@Configuration
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConfig.class);

    /** mock 当前账户（真接入后 auth/session per-request 注入——已知限制，迭代后补）。对齐前端真实用户 10086。 */
    private static final String CURRENT_USER_ID = "10086";

    /**
     * 提交制审批：建 PENDING 人工工单后<b>立即返回</b>（管理台决议驱动，无请求内等待窗口）。
     */
    @Bean
    WorkflowApprovalSubmitter workflowApprovalSubmitter(HumanTicketService tickets, Clock clock) {
        log.info("装配提交制工单审批 WorkflowApprovalSubmitter（建单即返回，决议=管理台状态事件）");
        return new TicketApprovalSubmitter(tickets, clock);
    }

    /** Agent 资格裁决器（政策知识+实时事实→LLM 三态裁决；失败降级 UNCERTAIN fail-safe 到人工）。 */
    @Bean
    AfterSaleEligibilityJudge afterSaleEligibilityJudge(ChatLlmService llm, Clock clock) {
        log.info("装配 LLM 资格裁决 AfterSaleEligibilityJudge（政策知识+实时事实交 Agent 裁决，失败→UNCERTAIN 转人工）");
        return new LlmAfterSaleEligibilityJudge(llm, clock);
    }

    /** 退款流图（refund_request → {@link PolicyDomain#REFUND}）。 */
    @Bean(name = "refundAfterSaleWorkflow")
    AfterSaleWorkflow refundAfterSaleWorkflow(UserQueryService userService,
                                              OrderQueryService orderService,
                                              PolicyQueryService policyService,
                                              AfterSaleEligibilityJudge eligibilityJudge,
                                              WorkflowApprovalSubmitter approvalSubmitter) {
        log.info("装配退款流 AfterSaleWorkflowGraph（REFUND + Agent 资格裁决 + 提交制工单审批，currentUserId={}）",
                CURRENT_USER_ID);
        return new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.REFUND, eligibilityJudge, approvalSubmitter, CURRENT_USER_ID);
    }

    /** 退货流图（return_request → {@link PolicyDomain#RETURN}）。 */
    @Bean(name = "returnAfterSaleWorkflow")
    AfterSaleWorkflow returnAfterSaleWorkflow(UserQueryService userService,
                                              OrderQueryService orderService,
                                              PolicyQueryService policyService,
                                              AfterSaleEligibilityJudge eligibilityJudge,
                                              WorkflowApprovalSubmitter approvalSubmitter) {
        log.info("装配退货流 AfterSaleWorkflowGraph（RETURN + Agent 资格裁决 + 提交制工单审批，currentUserId={}）",
                CURRENT_USER_ID);
        return new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.RETURN, eligibilityJudge, approvalSubmitter, CURRENT_USER_ID);
    }

    /**
     * 并发腿1 异步执行器（[[p0-intent-switch-clarify-design]] §7.2）：{@link WorkflowExecutionStep}
     * 用 {@code CompletableFuture.supplyAsync(supplier, executor)} 异步跑工作流图。
     * CachedThreadPool 按需扩线程、空闲回收；真并发后按需换有界池。
     */
    @Bean
    java.util.concurrent.Executor workflowTaskExecutor() {
        log.info("装配 workflowTaskExecutor（CachedThreadPool，并发腿1 异步跑图）");
        return java.util.concurrent.Executors.newCachedThreadPool();
    }
}
