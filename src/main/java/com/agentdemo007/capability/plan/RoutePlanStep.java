package com.agentdemo007.capability.plan;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.model.StandardQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 便利构造（旧测试/调用点零改动）：无 pending 存储 → NO_OP（恒无提示注入）。 */
    public RoutePlanStep(IntentRouteMapper mapper, RoutePromptBuilder promptBuilder, RoutePlanner planner) {
        this(mapper, promptBuilder, planner, null);
    }

    @Autowired
    public RoutePlanStep(IntentRouteMapper mapper, RoutePromptBuilder promptBuilder, RoutePlanner planner,
                         com.agentdemo007.capability.workflow.PendingWorkflowStore pendingStore) {
        this.mapper = mapper;
        this.promptBuilder = promptBuilder;
        this.planner = planner;
        this.pendingStore = (pendingStore != null) ? pendingStore : com.agentdemo007.capability.workflow.PendingWorkflowStore.NO_OP;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        String fallbackIntent = mapper.fallbackIntent(context.intent());
        String query = resolveQuery(context);
        String pendingIntent = pendingStore.get(context.sessionId())
                .map(p -> p.intent()).orElse(null);
        String prompt = promptBuilder.build(context.history(), query, pendingIntent);
        RoutePlan plan = planner.plan(fallbackIntent, prompt);
        context.setRoutePlan(plan);
        log.debug("路由计划产出：sessionId={} intent={} fallbackIntent={} source={} conf={} constraints={}",
                context.sessionId(), context.intent(), fallbackIntent,
                plan.source(), plan.confidence(), plan.policyConstraints());
        return new StepOutcome.Proceed();
    }

    /** 路由查询：优先标准化 Query（改写后自足，指代已消解），缺失回退原问题。 */
    private static String resolveQuery(PipelineContext context) {
        StandardQuery sq = context.standardQuery();
        return sq != null ? sq.text() : context.rawInput();
    }
}
