package com.agentdemo007.capability.plan;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.agentdemo007.capability.plan.RoutePlanCandidate.FallbackPolicy;
import static com.agentdemo007.capability.plan.RoutePlanCandidate.RiskLevel;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RouteCandidateParser} 测试（#133·Slice 2·route_model JSON→候选解析）。
 *
 * <p>route_model（关思考）输出 8 字段 snake_case JSON（参考系统 Pydantic 规格，[[routeplan-design]]），
 * 经 {@link RouteCandidateParser#parse} 解析为 {@link RoutePlanCandidate}：
 * <ul>
 *   <li>snake_case→camelCase 字段映射（{@code needs_rag}→{@code needsRag}）；</li>
 *   <li>小写枚举 case-insensitive 映射（{@code "low"}→{@link RiskLevel#LOW}，
 *       {@code "safe_deterministic_path"}→{@link FallbackPolicy#SAFE_DETERMINISTIC_PATH}）；</li>
 *   <li>多余字段忽略（模型加 reasoning/confidence 不崩）；</li>
 *   <li>fenced ```json / prose 包裹的 JSON 仍可提取；</li>
 *   <li>缺失必选枚举（risk_level/fallback_policy/intent）→ empty（不向下游 converge 传 null 枚举，
 *       规避 {@code riskLevel().ordinal()} NPE——路由永不崩，[[degradation-and-eval-principles]]）。</li>
 * </ul>
 *
 * <p>用真 {@link RouteCandidateParser#create}（真 Jackson 3 mapper）验映射——非 mock mapper，
 * 验的是「真依赖原语」一轮 round-trip（[[dont-hardwrite-use-dep-methods]] 铁律②）。
 */
class RouteCandidateParserTest {

    private final RouteCandidateParser parser = RouteCandidateParser.create();

    private static String json(String intent, boolean rag, boolean tools, String toolsJson,
                               String domainsJson, String risk, boolean workflow, String fallback) {
        return "{\"intent\":\"" + intent + "\",\"needs_rag\":" + rag + ",\"needs_business_tools\":" + tools
                + ",\"required_tools\":" + toolsJson + ",\"knowledge_domains\":" + domainsJson
                + ",\"risk_level\":\"" + risk + "\",\"requires_workflow\":" + workflow
                + ",\"fallback_policy\":\"" + fallback + "\"}";
    }

    @Test
    void parse_validSnakeCaseJson_returnsCandidate() {
        Optional<RoutePlanCandidate> c = parser.parse(json(
                "order_query", false, true, "[\"get_order_logistics\"]", "[]", "low", false, "tool_first"));
        assertThat(c).isPresent();
        RoutePlanCandidate r = c.get();
        assertThat(r.intent()).isEqualTo("order_query");
        assertThat(r.needsRag()).isFalse();
        assertThat(r.needsBusinessTools()).isTrue();
        assertThat(r.requiredTools()).containsExactly("get_order_logistics");
        assertThat(r.knowledgeDomains()).isEmpty();
        assertThat(r.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(r.requiresWorkflow()).isFalse();
        assertThat(r.fallbackPolicy()).isEqualTo(FallbackPolicy.TOOL_FIRST);
    }

    @Test
    void parse_fencedJson_returnsCandidate() {
        String fenced = "```json\n" + json("refund_request", true, true,
                "[\"get_order_detail\"]", "[\"after_sale_policy\"]", "high", true, "workflow_first") + "\n```";
        Optional<RoutePlanCandidate> c = parser.parse(fenced);
        assertThat(c).isPresent();
        assertThat(c.get().intent()).isEqualTo("refund_request");
        assertThat(c.get().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(c.get().requiresWorkflow()).isTrue();
        assertThat(c.get().fallbackPolicy()).isEqualTo(FallbackPolicy.WORKFLOW_FIRST);
    }

    @Test
    void parse_jsonWrappedInProse_returnsCandidate() {
        String prose = "Here is the route plan:\n" + json("general_chat", false, false, "[]", "[]",
                "low", false, "safe_deterministic_path") + "\nThat's all.";
        Optional<RoutePlanCandidate> c = parser.parse(prose);
        assertThat(c).isPresent();
        assertThat(c.get().intent()).isEqualTo("general_chat");
        assertThat(c.get().fallbackPolicy()).isEqualTo(FallbackPolicy.SAFE_DETERMINISTIC_PATH);
    }

    @Test
    void parse_extraFieldsIgnored_returnsCandidate() {
        String withExtra = json("product_query", true, true, "[\"search_products\"]",
                "[\"promotion_and_member_policy\"]", "low", false, "tool_first")
                .replaceFirst("\\}$", ",\"reasoning\":\"user wants price\",\"confidence\":0.9}");
        Optional<RoutePlanCandidate> c = parser.parse(withExtra);
        assertThat(c).isPresent();
        assertThat(c.get().intent()).isEqualTo("product_query");
    }

    @Test
    void parse_missingRiskLevel_returnsEmpty() {
        // 不含 risk_level → 枚举 null → 守卫 empty（不向 converge 传 null 枚举致 NPE）
        String noRisk = "{\"intent\":\"order_query\",\"needs_rag\":false,\"needs_business_tools\":true,"
                + "\"required_tools\":[\"get_order_logistics\"],\"knowledge_domains\":[],"
                + "\"requires_workflow\":false,\"fallback_policy\":\"tool_first\"}";
        assertThat(parser.parse(noRisk)).isEmpty();
    }

    @Test
    void parse_garbage_returnsEmpty() {
        assertThat(parser.parse("not json at all")).isEmpty();
    }

    @Test
    void parse_blankOrNull_returnsEmpty() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    void parse_lowercaseEnumsMapped_caseInsensitive() {
        Optional<RoutePlanCandidate> c = parser.parse(json(
                "security_request", false, false, "[]", "[]", "high", false, "transfer_to_human"));
        assertThat(c).isPresent();
        assertThat(c.get().riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(c.get().fallbackPolicy()).isEqualTo(FallbackPolicy.TRANSFER_TO_HUMAN);
    }
}
