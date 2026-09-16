package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.OrderQueryService;
import com.agentdemo007.capability.business.PolicyDomain;
import com.agentdemo007.capability.business.PolicyQueryService;
import com.agentdemo007.capability.business.UserQueryService;
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
 * 时装配退货/退款<b>两</b>个 {@link AfterSaleWorkflowGraph}（D1 参数化：共享 typed 服务 + 审批 seam，
 * 政策域 + 校验规则 + currentUserId 区分流）+ NO_OP seams（submit 返固定 id、approval 恒批准）——
 * dev/最小路径跑通 6 节点 DAG 结构 + Retry 语义。缺省（enabled 缺省 false）不装配本类→零工作流 bean→
 * {@code WorkflowExecutionStep@670} 亦不装配（同属性门控）→旧链线性等价不变。
 *
 * <p><b>return/refund 流选择</b>：两 bean 同为 {@link AfterSaleWorkflow}，{@link WorkflowExecutionStep}
 * 按 {@code rp.intent()} 选（return_request→退货图 / refund_request→退款图），故两 bean 须用
 * {@code @Bean(name=...)} 显式命名 + 调用方 {@code @Qualifier} 注入（同类型双 bean 须消歧）。
 *
 * <p>已知限制（mock 期）：{@code currentUserId} 固定 10086——真接入后由 auth/session per-request 注入
 * （图构造参数，迭代后补，[[business-tools-workflow-dag]] §2.3 已记）。真退款/退货后端（建售后请求）
 * /真人工审批（建审批工单 + async resume，Timeout→ShortCircuit）= 迭代后补；本配置 NO_OP 仅跑通图结构与触发链路。
 */
@Configuration
@ConditionalOnProperty(name = "app.workflow.enabled", havingValue = "true")
public class WorkflowConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConfig.class);

    /** mock 当前账户（真接入后 auth/session per-request 注入——已知限制，迭代后补）。对齐前端真实用户 10086。 */
    private static final String CURRENT_USER_ID = "10086";

    @Bean
    AfterSaleSubmitService afterSaleSubmitService() {
        log.info("装配 NO_OP AfterSaleSubmitService（dev/最小路径：返固定售后请求 id，真后端=迭代后补）");
        return ctx -> "WF-NOOP-" + ctx.sessionId();
    }

    @Bean
    WorkflowApprovalDecision workflowApprovalDecision() {
        log.info("装配 NO_OP WorkflowApprovalDecision（dev/最小路径：恒批准，真人工审批=迭代后补）");
        return workflowResult -> new WorkflowApprovalDecision.Approved("auto");
    }

    /**
     * 退款流图（refund_request → {@link PolicyDomain#REFUND} + {@link RefundValidationRule}·30 天窗口）。
     * 复用既有 {@link RefundService} 同形 seam（{@link AfterSaleSubmitService#submit}）。
     */
    @Bean(name = "refundAfterSaleWorkflow")
    AfterSaleWorkflow refundAfterSaleWorkflow(UserQueryService userService,
                                              OrderQueryService orderService,
                                              PolicyQueryService policyService,
                                              AfterSaleSubmitService submitService,
                                              WorkflowApprovalDecision approval) {
        log.info("装配退款流 AfterSaleWorkflowGraph（REFUND + RefundValidationRule，currentUserId={}）", CURRENT_USER_ID);
        return new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.REFUND, new RefundValidationRule(Clock.systemUTC()),
                submitService, approval, CURRENT_USER_ID);
    }

    /**
     * 退货流图（return_request → {@link PolicyDomain#RETURN} + {@link ReturnValidationRule}·7 天无理由窗口）。
     */
    @Bean(name = "returnAfterSaleWorkflow")
    AfterSaleWorkflow returnAfterSaleWorkflow(UserQueryService userService,
                                              OrderQueryService orderService,
                                              PolicyQueryService policyService,
                                              AfterSaleSubmitService submitService,
                                              WorkflowApprovalDecision approval) {
        log.info("装配退货流 AfterSaleWorkflowGraph（RETURN + ReturnValidationRule，currentUserId={}）", CURRENT_USER_ID);
        return new AfterSaleWorkflowGraph(userService, orderService, policyService,
                PolicyDomain.RETURN, new ReturnValidationRule(Clock.systemUTC()),
                submitService, approval, CURRENT_USER_ID);
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
