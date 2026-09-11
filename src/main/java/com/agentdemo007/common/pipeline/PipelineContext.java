package com.agentdemo007.common.pipeline;

import com.agentdemo007.common.degradation.DegradationScenario;
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
 *   <li>Phase 7：{@code intent}（意图枚举）、{@code routeType}（路由类型）</li>
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
    // Phase 8 上下文构建工厂强类型字段（§5.14：ragFragments/toolResults 为消费者字段，
    // 由 Phase 9-11 生产者步骤填充；assembledPrompt 为本工厂拼接产出）
    private List<String> ragFragments = new ArrayList<>();
    // Phase 20 RAG citation（§5.14：强类型收口，来源+摘要串；RagStep 命中时填，空则回答无来源标注）
    private List<String> ragCitations = new ArrayList<>();
    private List<String> toolResults = new ArrayList<>();
    private List<ChatMessage> assembledPrompt = new ArrayList<>();
    // Phase 11/12 能力与输出层强类型字段（§5.14：hitlTicketId/modelResponse 收口于此）
    private String hitlTicketId;
    private String modelResponse;
    // Phase 13 审计事件强类型字段（§5.14：流水线内审计点收集于此，终端后置钩子刷出到 AuditProducer 异步落库）
    private List<AuditEvent> auditEvents = new ArrayList<>();

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
}
