package com.agentdemo007.common.pipeline;

import com.agentdemo007.capability.kb.KbLevel;
import com.agentdemo007.capability.plan.RoutePlan;
import com.agentdemo007.capability.tool.ToolCallResult;
import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.progress.ProgressEmitter;
import com.agentdemo007.common.trace.TraceId;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.audit.AuditEvent;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.model.QueryEnrichment;
import com.agentdemo007.session.model.StandardQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一上下文载体（收口三件套之一）。
 *
 * <p>贯穿全部 7 层步骤的唯一状态对象：每步读写同一份字段契约，禁止各步私造数据结构互相传参。
 * 单请求单实例、非线程安全（由 {@link PipelineOrchestrator} 单线程顺序驱动）。
 *
 * <p>当前字段覆盖 Phase 1 基线；随各 Phase 推进，新增字段以<strong>强类型（非 Map）</strong>声明于此，
 * 始终维持单一数据真相源，避免层间数据结构漂移：
 * <ul>
 *   <li>Phase 3/6：{@code history}（标准 LLM 消息历史）</li>
 *   <li>Phase 6：{@code standardQuery}（标准化问题）</li>
 *   <li>Phase 7：{@code intent}（意图枚举）、{@code routeType}（路由类型）、{@code routePlan}（路由计划）</li>
 *   <li>Phase 8：{@code assembledPrompt}（三层隔离拼接结果）、{@code ragFragments}/{@code toolResults}
 *      （本层为消费者，Phase 9-11 生产者步骤在 {@code @Order} 6xx 填充后纳入客观数据层）</li>
 *   <li>Phase 11：{@code hitlState}</li>
 *   <li>Phase 4/12：{@code modelResponse}</li>
 * </ul>
 */
public class PipelineContext {

    private final String traceId;
    private final String sessionId;
    private final String rawInput;
    /** [[business-tools-workflow-dag]] per-request 当前用户 id（前端 ChatRequest 传入；工作流校验订单归属用）。
     *  null=无鉴权上下文（eval/未传），图 baked currentUserId 兜底——迭代后真鉴权强制非空。 */
    private String userId;
    // Phase 21 客户等级可见性（§5.14 强类型收口）：登录态 uid + 会员服务解析（请求入口/HITL 恢复
    // 各解析一次），永不从对话内容取（自称 VIP 不采信）；缺省 V0 fail-closed（eval/未登录只出 PUBLIC）。
    private KbLevel memberLevel = KbLevel.V0;
    // Phase 22 用户画像（L3 长期记忆·表达层）：请求入口加载一次进 System 运行时块（≤200 字）。
    // 永不承载权限语义（等级唯一来源=memberLevel）；画像读取失败→null（无画像照常答，不阻塞）。
    private String memberProfile;
    // Phase 22 记忆压缩触发源（T99 双轨之"真实 usage"轨）：本轮主模型（终答）调用的 usage 总 token。
    // 终局钩子据此判压缩触发（≥ 预算×阈值）；null=本轮无主模型调用（话术短路/取消/降级）→ 不触发。
    private Integer lastUsageTokens;
    private String finalReply;
    private boolean degraded;
    private DegradationScenario scenario;
    private List<ChatMessage> history = new ArrayList<>();
    // Phase 6 会话理解层强类型字段（§5.14：禁止各步私造数据结构，统一收口于此）
    private String summary;
    private StandardQuery standardQuery;
    // Phase 20 约束改写补全槽（§5.14：QueryEnrichment 强类型收口于此；约束改写产、Hybrid RAG 消费）
    private QueryEnrichment queryEnrichment = QueryEnrichment.EMPTY;
    // Phase 7 意图识别与模型路由层强类型字段
    private Intent intent;
    private double intentConfidence;
    private RouteRule.RouteType routeType;
    private String selectedModelId;
    // Phase 7 路由计划字段（§5.14：RoutePlan 强类型收口于此；RoutePlanStep@605 产出，
    // 下游 CapabilityStage@6xx/ToolExecutionStep/RagStep/HITL 消费能力决策——needs_rag/
    // needs_business_tools/required_tools/knowledge_domains/risk_level/requires_workflow/fallback_policy）
    private RoutePlan routePlan;
    // Phase 8 上下文构建工厂强类型字段（§5.14：ragFragments/toolResults 为消费者字段，
    // 由 Phase 9-11 生产者步骤填充；assembledPrompt 为本工厂拼接产出）
    private List<String> ragFragments = new ArrayList<>();
    // Phase 20 RAG citation（§5.14：强类型收口，来源+摘要串；RagStep 命中时填，空则回答无来源标注）
    private List<String> ragCitations = new ArrayList<>();
    private List<String> toolResults = new ArrayList<>();
    // [[business-tools-workflow-dag]] §2.2：tool-sourced 高置信外部系统事实通道（RUNTIME 工具结果：
    // 订单/用户/商品查询），SystemAnchorLayer 消费进 Runtime 块；区别于 toolResults（COMPUTE 简单计算）
    private List<String> runtimeFacts = new ArrayList<>();
    // Phase 9 工具韧性错误通道（2026-09-17 有界 Agent loop 配套，§5.14 强类型）：失败工具调用结果
    // （ToolCallResult.isError()，content=结构化错误 JSON）单独收口，不混 runtimeFacts 高置信事实；
    // ObjectiveDataLayer 渲染「工具执行异常」块——异常信息交终答 LLM 如实向客户说明/致歉，系统不吞异常
    private List<ToolCallResult> toolErrors = new ArrayList<>();
    // 有界 Agent loop 探测模型在工具环节（第 2 轮起）转出的文本回复（自纠正放弃后的澄清/策略话术）；
    // ObjectiveDataLayer 渲染「工具环节反馈」块交终答 LLM 整合。null=无。
    private String toolLoopReply;
    // [[refusal-design]] 工具无数据通道：工具连通且执行成功、但业务侧未命中数据（订单不存在/
    // 无可售商品/政策库无该条目，{@code hit=false}）的事实清单——区别于 {@code toolErrors}（调用失败）
    // 与 {@code runtimeFacts}（查到数据）。ObjectiveDataLayer 渲染「工具无数据」块，框定终答 LLM
    // 必须如实告知未查到、不得编造近似结果。空=本轮所有工具调用都有数据。
    private List<String> toolDataMisses = new ArrayList<>();
    // [[refusal-design]] 知识 grounding 未命中标记：RagStep 漏斗跑过但终态零片段（空召回/终闸不达标/
    // 注入扫描清空/链路异常）时置位。RefusalGateStep(@690) 据此裁决拒答（strict 短路 / prompt 注入
    // 拒答约束）；SystemAnchorLayer 据此在 Runtime 块追加"无依据不得作答"约束。闲聊/计划免 RAG 的
    // 正常跳过不置位（非"需要知识却没知识"）。
    private boolean groundingMiss;
    private List<ChatMessage> assembledPrompt = new ArrayList<>();
    // Phase 11/12 能力与输出层强类型字段（§5.14：hitlTicketId/modelResponse 收口于此）
    private String hitlTicketId;
    private String modelResponse;
    // 高风险固定工作流（[[per-intent-dag]]）：AfterSaleWorkflowGraph submit 节点产售后请求 id，审批门消费
    private String workflowResult;
    // [[business-tools-workflow-dag]] §2.4：业务驳回话术短路槽（Rejected 终态 → WorkflowExecutionStep 写此，
    // OutputStep 守卫跳 LLM；非业务驳回为 null，走原 LLM 链不变）。业务驳回≠系统故障，不混 DegradationScenario。
    private String presetReply;
    // Phase 13 审计事件强类型字段（§5.14：流水线内审计点收集于此，终端后置钩子刷出到 AuditProducer 异步落库）
    private List<AuditEvent> auditEvents = new ArrayList<>();
    // #136 进度发射 seam（[[routeplan-design]]）：每请求实例，编排器/各步产进度事件经此发射；
    // 缺省 NO_OP（同步 /chat + 单测零开销）；ChatController.chatStream 注入 SseProgressEmitter 桥接 SseEmitter 真异步 flush
    private ProgressEmitter emitter = ProgressEmitter.NO_OP;

    public PipelineContext(String sessionId, String rawInput) {
        this(TraceId.current(), sessionId, rawInput);
    }

    public PipelineContext(String traceId, String sessionId, String rawInput) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.rawInput = rawInput;
    }

    /** 便捷工厂：traceId 取自 MDC（由 TraceFilter 写入），缺失兜底生成。 */
    public static PipelineContext of(String sessionId, String rawInput) {
        return new PipelineContext(TraceId.current(), sessionId, rawInput);
    }

    public String traceId() {
        return traceId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String rawInput() {
        return rawInput;
    }

    /** 当前用户 id（per-request·前端 ChatRequest 传入；工作流校验订单归属。null=无鉴权上下文→图兜底）。 */
    public String userId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * 客户等级（Phase 21 安全轴）：来源只有登录态 + 会员服务（{@code MemberLevelService}），
     * 缺省 {@link KbLevel#V0}（fail-closed：eval/未登录/解析失败只出 PUBLIC 级知识）。
     */
    public KbLevel memberLevel() {
        return memberLevel;
    }

    /** 注入会员等级（null 归一 V0，消费方零防御；等级永不从对话内容取）。 */
    public void setMemberLevel(KbLevel memberLevel) {
        this.memberLevel = (memberLevel != null) ? memberLevel : KbLevel.V0;
    }

    /**
     * 用户画像文本（Phase 22 L3 长期记忆·表达层；请求入口加载，System 运行时块渲染）。
     * null/空白=无画像（读取失败/未配置/新用户——无画像照常答）。
     */
    public String memberProfile() {
        return memberProfile;
    }

    /** 注入用户画像（空白归一 null；永不承载权限语义）。 */
    public void setMemberProfile(String memberProfile) {
        this.memberProfile = (memberProfile != null && !memberProfile.isBlank()) ? memberProfile : null;
    }

    /**
     * 本轮主模型 usage 总 token（Phase 22 T99 真实 usage 轨；OutputStep 终答调用后写入）。
     * null=本轮无主模型调用（话术短路/取消/降级）——终局钩子据此跳过压缩触发。
     */
    public Integer lastUsageTokens() {
        return lastUsageTokens;
    }

    /** 注入主模型 usage（null/负值归一 null）。 */
    public void setLastUsageTokens(Integer lastUsageTokens) {
        this.lastUsageTokens = (lastUsageTokens != null && lastUsageTokens > 0) ? lastUsageTokens : null;
    }

    public String finalReply() {
        return finalReply;
    }

    public void setFinalReply(String finalReply) {
        this.finalReply = finalReply;
    }

    public boolean degraded() {
        return degraded;
    }

    public DegradationScenario scenario() {
        return scenario;
    }

    /** 标记降级（Degrade 路径）：置位 degraded 并记录场景，不阻塞后续步骤。 */
    public void markDegraded(DegradationScenario scenario) {
        this.degraded = true;
        this.scenario = scenario;
    }

    /** 会话历史（标准 LLM 消息，Phase 3 起接入；收口：强类型，禁止各步私造）。 */
    public List<ChatMessage> history() {
        return history;
    }

    /** 覆盖会话历史（如从 Redis 拉取后回填）。 */
    public void setHistory(List<ChatMessage> history) {
        this.history = (history != null) ? new ArrayList<>(history) : new ArrayList<>();
    }

    /** 追加一条会话历史。 */
    public void appendHistory(ChatMessage message) {
        this.history.add(message);
    }

    // ---- Phase 6 会话理解层字段 ----

    /** 会话摘要锚点（新建会话由 SummaryHook 生成，作为后续上下文锚点）。 */
    public String summary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    /** 标准化 Query（改写后的自足问题，供意图识别消费）。 */
    public StandardQuery standardQuery() {
        return standardQuery;
    }

    public void setStandardQuery(StandardQuery standardQuery) {
        this.standardQuery = standardQuery;
    }

    // ---- Phase 20 约束改写补全槽 ----

    /**
     * 查询补全槽（约束改写产出：精确词 + 时间线，只补不替、不下业务结论）。
     *
     * <p>缺省 {@link QueryEnrichment#EMPTY}（非 null），消费者可安全调 {@code .keywords()} 无 NPE。
     * Hybrid RAG 关键词/规则路由通道消费（T89：精确词走精确通道）。
     */
    public QueryEnrichment queryEnrichment() {
        return queryEnrichment;
    }

    public void setQueryEnrichment(QueryEnrichment queryEnrichment) {
        this.queryEnrichment = (queryEnrichment != null) ? queryEnrichment : QueryEnrichment.EMPTY;
    }

    // ---- Phase 7 意图与路由字段 ----

    /** 识别出的意图（对外枚举，§5.3.4 不暴露内部置信度给下游业务）。 */
    public Intent intent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

    public double intentConfidence() {
        return intentConfidence;
    }

    public void setIntentConfidence(double intentConfidence) {
        this.intentConfidence = intentConfidence;
    }

    /** 路由类型（意图→四类模型路由，Phase 7 RouteDispatchStep 写入）。 */
    public RouteRule.RouteType routeType() {
        return routeType;
    }

    public void setRouteType(RouteRule.RouteType routeType) {
        this.routeType = routeType;
    }

    /** 选定的目标模型标识（供 Phase 12 网关步骤构建请求）。 */
    public String selectedModelId() {
        return selectedModelId;
    }

    public void setSelectedModelId(String selectedModelId) {
        this.selectedModelId = selectedModelId;
    }

    /**
     * 路由计划（Phase 7 RoutePlanStep@605 产出的能力决策，[[routeplan-design]]）。
     *
     * <p>rule→LLM→rule 兜底混合产出：source=LLM_WITH_POLICY_CONSTRAINTS(采纳,conf 0.9) /
     * DETERMINISTIC_FALLBACK(兜底,conf 0.75)。下游 CapabilityStage/ToolExecutionStep/RagStep/HITL
     * 据此决定跑哪些工具/是否 RAG/是否人工。未产出时为 null（消费者须 null 防御）。
     */
    public RoutePlan routePlan() {
        return routePlan;
    }

    public void setRoutePlan(RoutePlan routePlan) {
        this.routePlan = routePlan;
    }

    // ---- Phase 8 上下文构建工厂字段 ----

    /** RAG 安全片段文本（客观数据层消费；Phase 10 RAG 步骤填充，空则跳过 RAG 段）。 */
    public List<String> ragFragments() {
        return ragFragments;
    }

    public void setRagFragments(List<String> ragFragments) {
        this.ragFragments = (ragFragments != null) ? new ArrayList<>(ragFragments) : new ArrayList<>();
    }

    /**
     * RAG 命中片段的可追溯引用（来源标识 + 片段摘要串，Phase 20 citation）。
     *
     * <p>RagStep 召回命中时填充，与 {@link #ragFragments()} 一一对应；RAG_SKIP/空召回时为空
     * （回答无来源标注，不阻塞）。用于回答侧呈现来源 + 审计侧定位命中片段——
     * <b>可追溯 ≠ 一定正确</b>：来源仅标示信息出处，不背书 LLM 据此生成的回复绝对正确。
     */
    public List<String> ragCitations() {
        return ragCitations;
    }

    public void setRagCitations(List<String> ragCitations) {
        this.ragCitations = (ragCitations != null) ? new ArrayList<>(ragCitations) : new ArrayList<>();
    }

    /** 工具执行结果文本（客观数据层消费；Phase 9 工具步骤填充，空则跳过 Tool 段）。 */
    public List<String> toolResults() {
        return toolResults;
    }

    public void setToolResults(List<String> toolResults) {
        this.toolResults = (toolResults != null) ? new ArrayList<>(toolResults) : new ArrayList<>();
    }

    /**
     * 工具产出的高置信外部系统事实（[[business-tools-workflow-dag]] §2.2 RUNTIME 通道：订单/用户/商品
     * 查询结果）。{@link com.agentdemo007.context.SystemAnchorLayer} 消费进 Runtime 块（与 summary/intent/time
     * 同居 System 锚点层，用户钦定 RunTime_* = 高置信独立通道，不降级进 RAG/Tool 段）；空跳过。
     */
    public List<String> runtimeFacts() {
        return runtimeFacts;
    }

    public void setRuntimeFacts(List<String> runtimeFacts) {
        this.runtimeFacts = (runtimeFacts != null) ? new ArrayList<>(runtimeFacts) : new ArrayList<>();
    }

    /**
     * 工具失败结果通道（Phase 9 工具韧性·2026-09-17 有界 Agent loop 配套）：失败工具调用
     * （{@link ToolCallResult#isError()}，content=结构化错误 JSON）单独收口——<b>不混
     * {@link #runtimeFacts} 高置信事实</b>（错误不是事实）。ObjectiveDataLayer 消费渲染
     * 「工具执行异常」块，终答 LLM 据此如实向客户说明/致歉；空则跳过。
     */
    public List<ToolCallResult> toolErrors() {
        return toolErrors;
    }

    public void setToolErrors(List<ToolCallResult> toolErrors) {
        this.toolErrors = (toolErrors != null) ? new ArrayList<>(toolErrors) : new ArrayList<>();
    }

    /**
     * 有界 Agent loop 探测模型在工具环节（第 2 轮起）转出的文本回复（错误回喂后模型放弃自纠正的
     * 澄清/策略话术）。ObjectiveDataLayer 渲染「工具环节反馈」块交终答 LLM 整合；null=无。
     */
    public String toolLoopReply() {
        return toolLoopReply;
    }

    public void setToolLoopReply(String toolLoopReply) {
        this.toolLoopReply = toolLoopReply;
    }

    // ---- [[refusal-design]] 拒答机制字段 ----

    /**
     * 工具无数据事实清单（工具连通且成功执行、但业务侧未命中数据的查询结果）。区别于
     * {@link #toolErrors}（调用失败）——"没有数据"本身是真实事实，需框定终答 LLM 如实转告、
     * 不得编造近似结果；{@link com.agentdemo007.context.ObjectiveDataLayer} 渲染「工具无数据」块。
     */
    public List<String> toolDataMisses() {
        return toolDataMisses;
    }

    public void setToolDataMisses(List<String> toolDataMisses) {
        this.toolDataMisses = (toolDataMisses != null) ? new ArrayList<>(toolDataMisses) : new ArrayList<>();
    }

    /**
     * 知识 grounding 未命中标记：本轮 RAG 漏斗实际执行且终态零片段。由 {@code RagStep.skipRag}
     * 置位；闲聊/路由计划免 RAG 的正常跳过<b>不置位</b>。消费者：{@code RefusalGateStep}（拒答裁决）、
     * {@code SystemAnchorLayer}（Runtime 块拒答约束）。
     */
    public boolean groundingMiss() {
        return groundingMiss;
    }

    public void setGroundingMiss(boolean groundingMiss) {
        this.groundingMiss = groundingMiss;
    }

    /** 三层隔离拼接后的最终上下文（Sys→Runtime→His→RAG→Tool→User，供 Phase 12 网关步骤消费）。 */
    public List<ChatMessage> assembledPrompt() {
        return assembledPrompt;
    }

    public void setAssembledPrompt(List<ChatMessage> assembledPrompt) {
        this.assembledPrompt = (assembledPrompt != null) ? new ArrayList<>(assembledPrompt) : new ArrayList<>();
    }

    // ---- Phase 11/12 能力与输出层字段 ----

    /** HITL 人工工单标识（Phase 11 HitlStep 触发后写入；未触发为 null）。 */
    public String hitlTicketId() {
        return hitlTicketId;
    }

    public void setHitlTicketId(String hitlTicketId) {
        this.hitlTicketId = hitlTicketId;
    }

    /** 模型原始响应（Phase 12 网关步骤产出，供结构化输出网关校验/过滤）。 */
    public String modelResponse() {
        return modelResponse;
    }

    public void setModelResponse(String modelResponse) {
        this.modelResponse = modelResponse;
    }

    /**
     * 退款工作流提交结果（高风险固定工作流 {@code submit_refund} 节点产出：退款请求 id/状态）。
     * 审批门 {@code await} 消费；低风险意图不触发工作流，为 null。
     */
    public String workflowResult() {
        return workflowResult;
    }

    public void setWorkflowResult(String workflowResult) {
        this.workflowResult = workflowResult;
    }

    /**
     * 业务驳回预设话术（[[business-tools-workflow-dag]] §2.4 E1：售后校验失败 Rejected 终态由
     * {@code WorkflowExecutionStep} 写入此槽 + Proceed，{@code OutputStep} 开头守卫见非空即跳 LLM、
     * {@code securityFilter.filter(presetReply)} 后写 finalReply 话术短路）。非业务驳回为 null（走原 LLM 链不变）。
     */
    public String presetReply() {
        return presetReply;
    }

    public void setPresetReply(String presetReply) {
        this.presetReply = presetReply;
    }

    // ---- 并发合并产物载体（[[p0-intent-switch-clarify-design]] §7）----

    /**
     * 并发合并产物载体（非 null = 并发合并模式，§7）：由 {@code WorkflowExecutionStep} 并发分支填充，
     * {@code OutputStep} 合并分支消费。null = 非并发模式（单腿/正常链路不变）。
     */
    private ConcurrentReply concurrentReply;

    public ConcurrentReply concurrentReply() {
        return concurrentReply;
    }

    public void setConcurrentReply(ConcurrentReply concurrentReply) {
        this.concurrentReply = concurrentReply;
    }

    // ---- Phase 13 审计事件字段 ----

    /** 审计事件列表（流水线内审计点收集于此；终端后置钩子刷出到 AuditProducer 异步落库）。 */
    public List<AuditEvent> auditEvents() {
        return auditEvents;
    }

    /** 追加审计事件（§5.14：审计点统一经此收集，禁止各步私造数据结构互相传参）。 */
    public void addAuditEvent(AuditEvent event) {
        if (event != null) {
            this.auditEvents.add(event);
        }
    }

    // ---- #136 进度发射 seam 字段 ----

    /**
     * 进度发射器（[[routeplan-design]] #136）：编排器/各步产进度事件经此发射。缺省
     * {@link ProgressEmitter#NO_OP}（同步 /chat + 单测零开销，不外泄）；chatStream 注入
     * SseProgressEmitter 桥接 SseEmitter 真异步 flush。与 {@link #addAuditEvent}（审计落库）正交：
     * 同一 step 产出，审计走 §5.14 落库信道，进度走 SSE 实时信道，互不替代。
     */
    public ProgressEmitter emitter() {
        return emitter;
    }

    /** 注入进度发射器（null 安全→NO_OP，避免 NPE 反噬编排/降级收口）。 */
    public void setEmitter(ProgressEmitter emitter) {
        this.emitter = (emitter != null) ? emitter : ProgressEmitter.NO_OP;
    }
}
