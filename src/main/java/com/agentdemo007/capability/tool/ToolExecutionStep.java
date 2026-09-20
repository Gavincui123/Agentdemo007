package com.agentdemo007.capability.tool;

import com.agentdemo007.capability.business.PolicyFragment;
import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.resilience.ToolCircuitOpenException;
import com.agentdemo007.session.model.StandardQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 工具执行步骤（第四层·{@code @Order(650)}，紧随 {@code RouteDispatchStep}、先于 {@code ContextBuilder}）。
 *
 * <p>对标准化 Query 经 {@link ToolCallExecutor}（有界 Agent loop·2026-09-17 推翻单次前向）驱动
 * 至多 {@code app.tool.max-iterations} 轮 function-calling：失败结果（结构化错误 JSON）回喂探测模型
 * 自纠正/澄清；产出按 {@link ToolCallResult} 路由：
 * <ul>
 *   <li>失败结果（{@link ToolCallResult#isError()}）→ {@code context.toolErrors}（独立错误通道，
 *       <b>不混 runtimeFacts 高置信事实</b>；ObjectiveDataLayer 渲染「工具执行异常」块，
 *       终答 LLM 据此如实向客户说明——异常信息交 LLM，系统不吞）；</li>
 *   <li>RUNTIME → {@code runtimeFacts}（外部系统高置信事实，SystemAnchorLayer 消费）；</li>
 *   <li>RAG → 拆 JSON {@code {text,source}} → {@code ragFragments}+{@code ragCitations}，
 *       畸形 JSON 兜底 raw（②每步降级，不阻塞）；</li>
 *   <li>COMPUTE → {@code toolResults}；</li>
 *   <li>模型澄清/策略话术（{@link ToolTurn#loopReply()}）→ {@code context.toolLoopReply}
 *       （ObjectiveDataLayer 渲染「工具环节反馈」块）。</li>
 * </ul>
 * 落地收口（{@link StepOutcome}）：
 * <ul>
 *   <li>无工具调用 / 执行成功（含失败回喂后收口）→ Proceed；</li>
 *   <li>工具熔断中（Phase 17 {@link ToolCircuitOpenException}）→ {@code ShortCircuit(TOOL_FAILURE)} 话术短路
 *       （零 LLM 兜底，不进工具探测，等冷却半开探针恢复）。</li>
 * </ul>
 *
 * <p>输入取 {@code standardQuery}（缺失回退 {@code rawInput}）——2026-09-17 定案：改写产物只排除出<b>回答生成</b>（UserInstructionLayer 恒用原话）与词表，工具路径保留改写：工具 LLM 无历史语境（单条 UserMessage），指代类原话会选错工具/填错参数；且工具产出是取数事实而非客户话术，歧义不落客户可见文本。
 */
@Component
@Order(650)
public class ToolExecutionStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionStep.class);

    private final ToolCallExecutor executor;
    private final AgentMetrics metrics;

    public ToolExecutionStep(ToolCallExecutor executor) {
        this(executor, AgentMetrics.NO_OP);
    }

    @Autowired
    public ToolExecutionStep(ToolCallExecutor executor, AgentMetrics metrics) {
        this.executor = executor;
        this.metrics = metrics;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        // #135 渐进消费·source 门控（[[routeplan-design]]·风险闭环扩展）：routePlan 声明无需业务工具
        // → 跳过工具执行（省一次 function-calling LLM 调用）。LLM 候选（收敛后能力）与兜底候选
        // （基线能力）<b>同口径</b>——route_model 不可用时兜底 general_chat 也不裸附全量工具
        // （高风险操作不得经无人审查的工具循环执行；写操作工具接入后配合 PermissionChecker 的
        // riskLevel 审批收口）。CHIT_CHAT 快路径例外：低风险业务词（订单/物流/商品/优惠/促销）的
        // 「小模型快回复+工具取数」设计保留（IntentConfig 内置词表；退款/退货已从词表移除——
        // 风险分层走完整理解路径，不存在借道闲聊摸工具的口子）。
        // requiredTools per-route 子集（ToolProvider）= Slice 4 留后；本步仅 needsBusinessTools 门控。
        RoutePlan rp = context.routePlan();
        if (rp != null && context.intent() != Intent.CHIT_CHAT && !rp.needsBusinessTools()) {
            log.debug("routePlan 门控跳过工具执行：sessionId={} source={} needsBusinessTools=false",
                    context.sessionId(), rp.source());
            return new StepOutcome.Proceed();
        }
        String query = resolveQuery(context);
        try {
            ToolTurn turn = executor.execute(query);
            if (!turn.results().isEmpty()) {
                routeResults(context, turn.results());
                log.debug("工具环节完成：sessionId={} results={} loopReply={}",
                        context.sessionId(), turn.results(), turn.loopReply() != null);
            }
            if (turn.loopReply() != null) {
                context.setToolLoopReply(turn.loopReply()); // 模型澄清/策略话术交终答 LLM 整合
            }
            metrics.recordTool(!turn.hasError());
            return new StepOutcome.Proceed();
        } catch (ToolCircuitOpenException e) {
            log.warn("工具熔断中，触发 TOOL_FAILURE 话术短路（零 LLM，不进工具探测）：sessionId={} tool={}",
                    context.sessionId(), e.toolName()); // 审计
            // 指标记录不得反噬降级：Micrometer 对非法 tag/重复不一致标签会抛，须吞掉保证 ShortCircuit 返回
            try {
                metrics.recordTool(false);
                metrics.recordToolCircuitOpen(e.toolName());
            } catch (Exception metricEx) {
                log.warn("指标记录失败，忽略（不影响降级短路）：{}", metricEx.getMessage());
            }
            return new StepOutcome.ShortCircuit(DegradationScenario.TOOL_FAILURE);
        }
    }

    /**
     * 按结果性质路由（错误通道优先，[[business-tools-workflow-dag]] §2.2）：
     * <ul>
     *   <li>失败结果（{@link ToolCallResult#isError()}）→ {@code context.toolErrors}（独立错误通道，
     *       不污染 runtimeFacts 高置信事实；content 为结构化错误 JSON）；</li>
     *   <li>RUNTIME → {@code context.runtimeFacts}（外部系统高置信事实，SystemAnchorLayer 消费进 Runtime 块）；
     *       <b>无数据文案（{@link NoDataSignals#isNoData}，如"订单 X 不存在"）附加路由
     *       {@code context.toolDataMisses}</b>——"没有数据"仍是真实事实保留 runtimeFacts，
     *       同时框定终答必须如实告知未查到（[[refusal-design]] 工具分支拒答）；</li>
     *   <li>RAG → 拆 JSON {@code {text,source,hit}} → 命中（hit=true）→ ragFragments+ragCitations
     *       （带 citation）；<b>未命中（hit=false，政策库无此条目）→ {@code toolDataMisses}</b>，
     *       兜底话术不再混入 ragFragments 冒充政策正文；畸形 JSON 兜底 raw 入 ragFragments、
     *       citation 空（②每步降级，不阻塞）；</li>
     *   <li>COMPUTE → {@code context.toolResults}（简单计算，现状不变）。</li>
     * </ul>
     */
    private void routeResults(PipelineContext context, List<ToolCallResult> results) {
        for (ToolCallResult r : results) {
            if (r.isError()) {
                context.toolErrors().add(r); // 错误单独收口：异常信息交 LLM，不混高置信事实
                continue;
            }
            switch (r.category()) {
                case RUNTIME -> {
                    context.runtimeFacts().add(r.content());
                    if (NoDataSignals.isNoData(r.content())) {
                        context.toolDataMisses().add(r.content()); // 无数据事实附加收口（拒答框定）
                    }
                }
                case RAG -> {
                    Optional<PolicyFragment> parsed = PolicyFragment.fromJson(r.content());
                    if (parsed.isPresent() && !parsed.get().hit()) {
                        // 政策库无此条目：兜底话术进"工具无数据"通道，不混 ragFragments（拒答框定）
                        context.toolDataMisses().add(parsed.get().text());
                    } else if (parsed.isPresent()) {
                        context.ragFragments().add(parsed.get().text());
                        context.ragCitations().add(parsed.get().source());
                    } else {
                        context.ragFragments().add(r.content()); // 兜底 raw（citation 空）
                    }
                }
                case COMPUTE -> context.toolResults().add(r.content());
            }
        }
    }

    private String resolveQuery(PipelineContext context) {
        StandardQuery sq = context.standardQuery();
        return (sq != null) ? sq.text() : context.rawInput();
    }
}
