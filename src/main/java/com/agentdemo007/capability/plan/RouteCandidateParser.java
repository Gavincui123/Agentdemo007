package com.agentdemo007.capability.plan;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * route_model 输出解析器（#133·Slice 2·JSON→{@link RoutePlanCandidate}，[[routeplan-design]]）。
 *
 * <p>route_model（关思考，经 {@code ChatLlmService.decide}）输出 8 字段 snake_case JSON
 * （参考系统 Pydantic 规格：{@code needs_rag}/{@code risk_level}/{@code fallback_policy}…），
 * 经本解析器映射为 {@link RoutePlanCandidate}（camelCase 记录）：
 * <ul>
 *   <li>snake_case→camelCase：{@link PropertyNamingStrategies#SNAKE_CASE} 命名策略；</li>
 *   <li>小写枚举 case-insensitive：{@link MapperFeature#ACCEPT_CASE_INSENSITIVE_ENUMS}
 *       （{@code "low"}→{@link RoutePlanCandidate.RiskLevel#LOW}，
 *       {@code "safe_deterministic_path"}→{@link RoutePlanCandidate.FallbackPolicy#SAFE_DETERMINISTIC_PATH}）；</li>
 *   <li>多余字段忽略：{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} 关
 *       （模型加 reasoning/confidence 等不崩）。</li>
 * </ul>
 *
 * <p><b>健壮性</b>（[[degradation-and-eval-principles]] 路由永不崩）：
 * <ul>
 *   <li>fenced {@code ```json} / prose 包裹 → 取首个 {@code {} 末个 {@code }} 间子串提取；</li>
 *   <li>缺失必选枚举（{@code risk_level}/{@code fallback_policy}/{@code intent}）→ empty：
 *       不向下游 {@link RoutePlanRuleMatcher#converge} 传 null 枚举（规避 {@code riskLevel().ordinal()} NPE）；</li>
 *   <li>任何解析异常（非合法 JSON / 类型不匹配）→ empty，交 {@link RoutePlanner} 走 rule 兜底。</li>
 * </ul>
 *
 * <p>用 Jackson 3（{@code tools.jackson}，Boot 4 全栈；与 LC4j 的 {@code com.fasterxml} 包名不同无冲突，
 * [[langchain4j-boot4-compat-findings]]）。{@code readValue(String,Class)} 在 Jackson 3 已移除，
 * 故用 {@code readTree(String)→JsonNode} + {@code treeToValue(JsonNode,Class)}（[[dont-hardwrite-use-dep-methods]]
 * 铁律①：先走依赖 API 面，javap 钉签名再写）。
 */
public class RouteCandidateParser {

    private final ObjectMapper mapper;

    public RouteCandidateParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 默认解析器：snake_case + 大小写不敏感枚举 + 忽略多余字段 + 缺省 primitive 落 false。 */
    public static RouteCandidateParser create() {
        return new RouteCandidateParser(JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // LLM 不输出 ambiguous 等新 primitive 字段 → 缺省 false（[[p0-intent-switch-clarify]] 约定），
                // 否则 Jackson 3 缺省 FAIL_ON_NULL_FOR_PRIMITIVES 对缺失 boolean 注 null 即抛。
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build());
    }

    /**
     * 解析 route_model 原始输出为候选。
     *
     * @param raw route_model 原始文本（可含 ```json``` 围栏 / prose / 多余字段；可 null/blank）
     * @return 解析成功的候选；不可解析或缺必选枚举则 empty
     */
    public Optional<RoutePlanCandidate> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = mapper.readTree(json);
            RoutePlanCandidate c = mapper.treeToValue(node, RoutePlanCandidate.class);
            if (c == null || c.intent() == null || c.riskLevel() == null || c.fallbackPolicy() == null) {
                return Optional.empty(); // 缺必选枚举/String → 不向下游传 null 致 NPE
            }
            return Optional.of(c);
        } catch (Exception e) {
            return Optional.empty(); // 非合法 JSON / 类型不匹配 → rule 兜底
        }
    }

    /** 从可能围栏/prose 包裹的输出中提取首个 JSON 对象子串（schema 无嵌套对象，首{ 末} 安全）。 */
    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }
}
