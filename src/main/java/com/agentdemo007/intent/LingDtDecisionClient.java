package com.agentdemo007.intent;

import com.agentdemo007.capability.rag.CircuitBreakerGuard;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LingDT-Decision 决策型大模型出站 client（2026-10-24 接入，仅意图识别 LLM 分类层使用）。
 *
 * <p><b>API 形态</b>（蚂蚁数科 DTMaaS System One，专有决策 API 非 OpenAI 兼容；2026-10-24 实测口径）：
 * {@code POST {endpoint}}（Bearer 认证），<b>model 路由名在请求体顶层</b>（URL query 不认，网关报
 * {@code missing_model}）——意图分类建模为单个 choice 型问题：{@code state={history:[近6条], latest_message}} 承载会话背景与最新消息，
 * {@code questions.intent.criteria} 即 {@link IntentRecognizerImpl#MODEL_CANDIDATES}（意图名→一句话定义，
 * 单一真源不另维护词表），分类原则并入 instructions。响应 {@code answers.intent.{choice, confidence}}，
 * confidence 为模型深度对齐的分布确定度，直接透传为 {@link IntentCategory#confidence()}（替代原
 * 写死 0.7）。
 *
 * <p><b>降级契约</b>：任何失败形态（连接/超时、HTTP 非 2xx、{@code errorCode≠0}、answers 缺失/类型
 * 不符、choice 越界、confidence 越界、JSON 解析失败、熔断开路）抛 {@link IllegalStateException}，
 * 由 {@link IntentRecognizerImpl} <b>同轮回退</b>通用小模型通道——LingDT 的失败不暴露给用户。
 *
 * <p><b>熔断快速短路 + 自动恢复</b>：{@link CircuitBreakerGuard}（窗口 60s/冷却 30s/单次失败即开断，
 * 对齐 RAG 可选增强通道口径——存在同级兜底时快速降级代价最小）。开路期间意图识别零等待直接走
 * 通用小模型（不白吃读超时）；冷却期后半开探测一次，成功即自动切回 LingDT，无人值守。
 *
 * <p><b>可观测</b>：与网关 {@code FailoverExecutor} 同源埋点——「LLM出站」日志（scene=意图识别·LingDT；
 * 成功行带 intent/confidence/query 摘录，失败行带 err 原因，出站行自身即决策证据）+
 * {@link AgentMetrics#recordModelCall}（与网关共用的全局成败/时长口径，无 model 维度——LingDT 单独
 * 的成功率看日志行；熔断开路的零等待快速失败同样入账）；HTTP 层挂
 * {@code TraceClientInterceptor}（OTel CLIENT span + W3C 注入）由装配处完成。
 */
public class LingDtDecisionClient {

    private static final Logger log = LoggerFactory.getLogger(LingDtDecisionClient.class);

    /** System One 问题 ID（^[A-Za-z0-9_-]{1,64}$；响应经同名 key 取答案）。 */
    static final String QUESTION_ID = "intent";

    private final LingDtProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;
    private final CircuitBreakerGuard guard;

    public LingDtDecisionClient(LingDtProperties props, RestTemplate restTemplate,
                                ObjectMapper mapper, AgentMetrics metrics) {
        this(props, restTemplate, mapper, metrics,
                new CircuitBreakerGuard("lingdt", 60_000, 30_000));
    }

    /** 测试注入点：自定义熔断参数（短窗口/冷却验证半开恢复）。 */
    LingDtDecisionClient(LingDtProperties props, RestTemplate restTemplate, ObjectMapper mapper,
                         AgentMetrics metrics, CircuitBreakerGuard guard) {
        this.props = props;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.metrics = metrics;
        this.guard = guard;
    }

    /** 一次决策分类：熔断开路立即抛（调用方回退）；成功返回意图 + 真实置信度。 */
    public Decision classify(String query, List<ChatMessage> history) {
        long start = System.nanoTime();
        try {
            Decision decision = guard.call(() -> classifyOnce(query, history));
            long millis = elapsedMs(start);
            metrics.recordModelCall(millis, true);
            // 成功行带决策结论（intent/confidence）与问题摘录：意图识别结果直接可从日志复核，
            // 不用再去翻识别器上游（2026-10-24 截图评审补强：出站行自身即证据）。
            log.info("LLM出站 scene=意图识别·LingDT model={} durMs={} ok=true attempt=1/1 intent={} confidence={} query={}",
                    props.getModel(), millis, decision.intent(), decision.confidence(), snippet(query, 60));
            return decision;
        } catch (RuntimeException e) {
            // 指标与出站日志在 classify 统一收口：熔断开路的零等待快速失败同样入账
            // （不计败则 LingDT 全挂期间 /obs 全局成功率反而「正常」，2026-10-24 review 收口）。
            metrics.recordModelCall(elapsedMs(start), false);
            log.info("LLM出站 scene=意图识别·LingDT model={} durMs={} ok=false attempt=1/1 err={}",
                    props.getModel(), elapsedMs(start), e.getMessage());
            throw e;
        }
    }

    private long elapsedMs(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 纯出站（HTTP + 校验解析）；指标与出站日志由 {@link #classify} 统一记（含开路快速失败）。 */
    private Decision classifyOnce(String query, List<ChatMessage> history) {
        return parse(post(buildBody(query, history)));
    }

    /** 组装 System One 请求体（model 在 body 顶层——实测网关口径，URL query 不认；state 放事实、instructions 放问题与原则、criteria 放选项定义）。 */
    private Map<String, Object> buildBody(String query, List<ChatMessage> history) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("history", transcript(history));
        state.put("latest_message", query);
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", List.of(
                "结合 history 消解指代与省略，判断 latest_message 属于哪个认知意图类别（分类只针对最新消息）。",
                "只依据用户的显式诉求分类；不要根据『系统能否办理该业务』改变分类，办理能力由系统下游决定。",
                "『转人工/人工服务』类诉求不在候选中（由关键词规则处理），不要输出。",
                "OTHER 是默认归类：不属于前四类中任何一类的诉求都选 OTHER，拿不准该归哪类时也选 OTHER，不要强行归入前四类。"));
        Map<String, String> criteria = new LinkedHashMap<>();
        IntentRecognizerImpl.MODEL_CANDIDATES.forEach((intent, definition) ->
                criteria.put(intent.name(), definition));
        question.put("criteria", criteria);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("state", state);
        body.put("questions", Map.of(QUESTION_ID, question));
        return body;
    }

    /** 会话背景（对齐 buildTranscript 口径：近 6 条 [角色] content）。 */
    private List<String> transcript(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of("（无历史）");
        }
        List<String> lines = new ArrayList<>();
        for (ChatMessage m : history.subList(Math.max(0, history.size() - 6), history.size())) {
            lines.add("[" + IntentRecognizerImpl.label(m) + "] " + m.content());
        }
        return lines;
    }

    private String post(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(props.getApiKey());
        try {
            return restTemplate.postForObject(props.getEndpoint(), new HttpEntity<>(mapper.writeValueAsString(body), headers),
                    String.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("LingDT HTTP " + e.getStatusCode() + "：body="
                    + snippet(e.getResponseBodyAsString()), e);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new IllegalStateException("LingDT 连接失败/超时：检查 lingdt.endpoint/网络——" + e.getMessage(), e);
        }
    }

    /**
     * 响应校验与解析（文档 §4.2 顺序）：JSON 可解析 → errorCode==0 → answers.intent.type=choice →
     * choice ∈ MODEL_CANDIDATES（严格候选集，规则层独占意图天然不可达）→ confidence ∈ [0,1]。
     */
    private Decision parse(String response) {
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (Exception e) {
            throw new IllegalStateException("LingDT 响应不可解析：" + snippet(response), e);
        }
        int errorCode = root.path("errorCode").asInt(-1);
        if (errorCode != 0) {
            throw new IllegalStateException("LingDT 业务失败：errorCode=" + errorCode
                    + " errorDesc=" + root.path("errorDesc").asText("(缺失)"));
        }
        JsonNode answer = root.path("answers").path(QUESTION_ID);
        if (!"choice".equals(answer.path("type").asText())) {
            throw new IllegalStateException("LingDT 答案缺失或类型不符：answers." + QUESTION_ID
                    + "=" + snippet(answer.toString()));
        }
        String choice = answer.path("choice").asText(null);
        Intent parsed = matchCandidate(choice);
        if (parsed == null) {
            throw new IllegalStateException("LingDT choice 越界（不在候选意图集）：choice=" + choice);
        }
        double confidence = answer.path("confidence").asDouble(-1);
        if (confidence < 0 || confidence > 1 || Double.isNaN(confidence)) {
            throw new IllegalStateException("LingDT confidence 越界：confidence=" + confidence);
        }
        return new Decision(parsed, confidence);
    }

    /** choice → 意图（大小写不敏感全等，限定 MODEL_CANDIDATES 候选集）。 */
    private static Intent matchCandidate(String choice) {
        if (choice == null || choice.isBlank()) {
            return null;
        }
        for (Intent intent : IntentRecognizerImpl.MODEL_CANDIDATES.keySet()) {
            if (intent.name().equalsIgnoreCase(choice.trim())) {
                return intent;
            }
        }
        return null;
    }

    private static String snippet(String text) {
        return snippet(text, 200);
    }

    /** 摘录（剥离控制符/格式符 + 压空白 + 按码点截断）：默认 200 字符，日志出站行的 query 摘录用短口径（60）。
     *  剥离 {@code \p{Cntrl}}（ANSI 转义等）与 {@code \p{Cf}}（零宽字符）防日志注入——用户 query 与远端
     *  errorDesc 都会进日志，不能原样透传（2026-10-24 review 收口）；码点截断防 emoji 被拆成孤立代理项。 */
    private static String snippet(String text, int max) {
        if (text == null) {
            return "(null)";
        }
        String t = text.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").replaceAll("\\s+", " ").trim();
        if (t.codePointCount(0, t.length()) <= max) {
            return t;
        }
        return t.substring(0, t.offsetByCodePoints(0, max)) + "…";
    }

    /** 决策结论：意图 + 模型返回的分布确定度（透传为 IntentCategory.confidence）。 */
    public record Decision(Intent intent, double confidence) {
    }

    /** 装配辅助（{@code IntentConfig} 用）：带超时预算的专用 RestTemplate（裸 RestTemplate 无超时会无限挂起）。 */
    public static RestTemplate restTemplate(LingDtProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs(props.getConnectTimeoutMs(), "connect-timeout-ms"));
        factory.setReadTimeout(timeoutMs(props.getReadTimeoutMs(), "read-timeout-ms"));
        return new RestTemplate(factory);
    }

    /** 超时预算收口：正数且在 int 域内（SimpleClientHttpRequestFactory 收 int，静默截断/负值不可接受）。 */
    private static int timeoutMs(long millis, String name) {
        if (millis <= 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("lingdt." + name + " 须为正数且不超过 " + Integer.MAX_VALUE
                    + "：当前 " + millis);
        }
        return (int) millis;
    }
}
