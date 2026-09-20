package com.agentdemo007.capability.plan;

import com.agentdemo007.capability.workflow.AfterSaleWorkflowGraph;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 路由计划步骤（第三层·{@code @Order(605)}，紧随 {@code RouteDispatchStep(600)}、先于 {@code HitlStep(610)}）。
 *
 * <p>路由权威决策点：读 {@code context.intent} → {@link IntentRouteMapper} 映射 fallbackIntent
 * （12 业务意图 String 占位）→ {@link RoutePromptBuilder} 构造 route prompt（history + query）→
 * {@link RoutePlanner#plan} 混合产出（rule→LLM→rule 兜底）→ 写 {@code context.routePlan} → Proceed。
 *
 * <p>路由永不崩（[[degradation-and-eval-principles]] 统一收口）：{@link RoutePlanner} 兜底恒产非 null
 * RoutePlan（LLM 挂/候选 invalid → {@link RoutePlan.Source#DETERMINISTIC_FALLBACK}, conf 0.75）。本步骤
 * 永不 Degrade/ShortCircuit，恒 Proceed——能力决策的降级在下游消费（#135 CapabilityStage 据此降级，不在本步）。
 *
 * <p>chit-chat 由 {@link IntentRouteMapper} 映射 {@code general_chat} → {@link RoutePlanner} rule 短路
 * （零 LLM，[[phase6-7-chitchat-fast-path]]）。@SpringBootTest 默认 {@code llm.enabled} 缺省 →
 * {@code NoopModelExecutor} 返回占位非 JSON → {@link RouteCandidateParser} empty → rule 兜底
 * general_chat（无真实模型调用/无 hang）。
 *
 * <p>本步恒重路由当前轮（含续跑轮，pending 提示经 prompt 注入）：不再因 routePlan 预置跳过——
 * {@link com.agentdemo007.capability.workflow.PendingWorkflowStore} 中存在 pending 时，将其 intent
 * 作为提示注入 route prompt（诚实引导 LLM 按本轮诉求决策，不被未完成意图带跑）。
 *
 * <p>出站 LLM 调用只经 {@link LlmRouteCandidateSource}（→ {@link com.agentdemo007.gateway.llm.ChatLlmService#decide}
 * 关思考），不在此直连引擎（§9.11 收口）。
 */
@Component
@Order(605)
public class RoutePlanStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(RoutePlanStep.class);

    private final IntentRouteMapper mapper;
    private final RoutePromptBuilder promptBuilder;
    private final RoutePlanner planner;
    private final com.agentdemo007.capability.workflow.PendingWorkflowStore pendingStore;
    /** 售后能力收敛开关（随工作流装配）：false=工作流未启用，候选值不做收敛（旧链路行为不变）。 */
    private final boolean workflowEnabled;

    /** 便利构造（旧测试/调用点零改动）：无 pending 存储 → NO_OP（恒无提示注入）；收敛默认关。 */
    public RoutePlanStep(IntentRouteMapper mapper, RoutePromptBuilder promptBuilder, RoutePlanner planner) {
        this(mapper, promptBuilder, planner, null, false);
    }

    /** Spring 装配（亦供测试直构：收敛开关可控，直构时 @Value 注解无副作用）。 */
    @Autowired
    public RoutePlanStep(IntentRouteMapper mapper, RoutePromptBuilder promptBuilder, RoutePlanner planner,
                         com.agentdemo007.capability.workflow.PendingWorkflowStore pendingStore,
                         @Value("${app.workflow.enabled:false}") boolean workflowEnabled) {
        this.mapper = mapper;
        this.promptBuilder = promptBuilder;
        this.planner = planner;
        this.pendingStore = (pendingStore != null) ? pendingStore : com.agentdemo007.capability.workflow.PendingWorkflowStore.NO_OP;
        this.workflowEnabled = workflowEnabled;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        String fallbackIntent = mapper.fallbackIntent(context.intent());
        String query = resolveQuery(context);
        String pendingIntent = pendingStore.get(context.sessionId())
                .map(p -> p.intent()).orElse(null);
        String prompt = promptBuilder.build(context.history(), query, pendingIntent);
        RoutePlan plan = planner.plan(fallbackIntent, prompt);
        plan = convergeCapability(plan, context);
        context.setRoutePlan(plan);
        // 业务意图（plan.intent()）与认知意图（context.intent()）分列：此前 intent= 打认知意图，
        // 与 @670 消费的业务意图（如 refund_request）对不上，排查高风险路径时误导（2026-09-17 实测）。
        log.debug("路由计划产出：sessionId={} 业务意图={} 认知意图={} fallbackIntent={} source={} conf={} constraints={}",
                context.sessionId(), plan.intent(), context.intent(), fallbackIntent,
                plan.source(), plan.confidence(), plan.policyConstraints());
        return new StepOutcome.Proceed();
    }

    /**
     * 售后能力收敛（2026-09-18 用户裁决：routePlan 是本轮能力决策契约——澄清/工具/RAG/工作流
     * 都在此定死，下游只执行）。对售后意图修正候选值，让下游门控（needsBusinessTools/needsRag）
     * 拿到与终态一致的决策：
     * <ul>
     *   <li>{@code ambiguous=true}（澄清菜单终态）→ needsRag=false + needsBusinessTools=false
     *       ——菜单澄清不消费检索与工具事实；</li>
     *   <li>refund_request/return_request（secondaryIntent=null）→ needsRag=<b>恒 false</b>
     *       （售后政策由工作流 query_policy/政策 @Tool 单通道供给；主链 RAG 注入在 presetReply
     *       终态（澄清/衔接/确认）下从不被消费——实测白跑 8s 漏斗）；needsBusinessTools=
     *       <b>有无订单号</b>（无单号→澄清/衔接终态，跳过工具探测——实测 T3 探测小模型挂 17.8s 全浪费；
     *       有单号→保留工具取数供参考来源/事实）。</li>
     * </ul>
     * 订单号判定复用工作流同一提取契约（raw → standardQuery，含改写层记忆补全）。
     * 工作流未启用 / 并发腿（secondaryIntent≠null，腿2 需要能力）不收敛，行为不变。
     */
    private RoutePlan convergeCapability(RoutePlan plan, PipelineContext context) {
        if (!workflowEnabled || plan == null || plan.secondaryIntent() != null) {
            return plan;
        }
        String intent = plan.intent();
        boolean ambiguous = plan.ambiguous();
        boolean afterSale = "refund_request".equals(intent) || "return_request".equals(intent);
        if (!ambiguous && !afterSale) {
            return plan;
        }
        boolean needsRag = false;
        boolean needsTools = !ambiguous && AfterSaleWorkflowGraph.extractOrderIdFrom(context) != null;
        RoutePlanCandidate old = plan.candidate();
        RoutePlanCandidate converged = new RoutePlanCandidate(
                old.intent(), needsRag, needsTools, old.requiredTools(), old.knowledgeDomains(),
                old.riskLevel(), old.requiresWorkflow(), old.fallbackPolicy(),
                old.ambiguous(), old.secondaryIntent());
        log.info("售后能力收敛（routePlan 契约）：sessionId={} intent={} ambiguous={} hasOrder={} → needsRag=false needsBusinessTools={}",
                context.sessionId(), intent, ambiguous, needsTools, needsTools);
        return new RoutePlan(converged, plan.source(), plan.confidence(), plan.policyConstraints());
    }

    /**
     * 路由查询：恒取用户原话（2026-09-17 定案：改写产物只供 RAG 检索；route prompt 已携带
     * {@code context.history()}，指代消解交给 route_model 结合历史自行完成，不再依赖改写内联）。
     */
    private static String resolveQuery(PipelineContext context) {
        return context.rawInput();
    }
}
