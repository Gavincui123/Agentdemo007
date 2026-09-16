package com.agentdemo007.capability.plan;

import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoutePromptBuilder} 测试（#134·Sub-slice 2·route_model prompt 构造器）。
 *
 * <p>route prompt 须给模型足够约束以产出一个合法的 8 字段 snake_case JSON
 * （{@link RouteCandidateParser} 可解析）。本测试钉死 prompt 必含的要素：
 * <ul>
 *   <li>8 字段规格（intent/needs_rag/needs_business_tools/required_tools/knowledge_domains/
 *       risk_level/requires_workflow/fallback_policy）；</li>
 *   <li>tool_candidates（从 {@link RoutePlanBaselines} 派生——单一真源，不臆造）；</li>
 *   <li>4 知识域 + 3 风险档 + 6 fallback_policy 枚举值 + 已知 intent 名（模型选项空间）；</li>
 *   <li>会话历史 transcript（复用 {@code QueryRewriter} 的 [角色] 内容 模式）；</li>
 *   <li>当前 query（标准化查询或原问题）。</li>
 * </ul>
 *
 * <p>断言只验「含某要素」（contains），不钉死措辞——prompt 文案可迭代而测试不脆裂。
 */
class RoutePromptBuilderTest {

    private final RoutePlanBaselines baselines = new RoutePlanBaselines();
    private final RoutePromptBuilder builder = new RoutePromptBuilder(baselines);

    @Test
    void buildContainsAllEightFieldSpec() {
        String p = builder.build(List.of(), "我的订单到哪了");
        assertThat(p).contains("intent");
        assertThat(p).contains("needs_rag");
        assertThat(p).contains("needs_business_tools");
        assertThat(p).contains("required_tools");
        assertThat(p).contains("knowledge_domains");
        assertThat(p).contains("risk_level");
        assertThat(p).contains("requires_workflow");
        assertThat(p).contains("fallback_policy");
    }

    @Test
    void buildContainsToolCandidates_derivedFromBaselines() {
        // tool_candidates = 全 baseline requiredTools 并集（单一真源，不臆造工具）
        String p = builder.build(List.of(), "q");
        assertThat(p).contains("get_order_logistics");
        assertThat(p).contains("get_refund_status");
        assertThat(p).contains("get_order_detail");
        assertThat(p).contains("search_products");
    }

    @Test
    void buildContainsFourKnowledgeDomains() {
        String p = builder.build(List.of(), "q");
        assertThat(p).contains("faq");
        assertThat(p).contains("after_sale_policy");
        assertThat(p).contains("received_return_policy");
        assertThat(p).contains("promotion_and_member_policy");
    }

    @Test
    void buildContainsRiskLevels() {
        String p = builder.build(List.of(), "q");
        assertThat(p).contains("low");
        assertThat(p).contains("medium");
        assertThat(p).contains("high");
    }

    @Test
    void buildContainsFallbackPolicyValues() {
        String p = builder.build(List.of(), "q");
        assertThat(p).contains("safe_deterministic_path");
        assertThat(p).contains("ask_order_id");
        assertThat(p).contains("knowledge_only");
        assertThat(p).contains("tool_first");
        assertThat(p).contains("workflow_first");
        assertThat(p).contains("transfer_to_human");
    }

    @Test
    void buildContainsKnownIntents_asOptions() {
        String p = builder.build(List.of(), "q");
        assertThat(p).contains("order_query");
        assertThat(p).contains("general_chat");
        assertThat(p).contains("security_request");
        assertThat(p).contains("refund_request");
    }

    @Test
    void buildContainsQuery() {
        String p = builder.build(List.of(), "我的退款什么时候到账");
        assertThat(p).contains("我的退款什么时候到账");
    }

    @Test
    void buildContainsHistoryTranscript_whenNonEmpty() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("我昨天买了耳机"),
                new ChatMessage.Ai("好的，已为您下单"));
        String p = builder.build(history, "物流到哪了");
        assertThat(p).contains("我昨天买了耳机");
        assertThat(p).contains("已为您下单");
        assertThat(p).contains("物流到哪了");
    }

    @Test
    void buildContainsNoHistoryMarker_whenEmpty() {
        String p = builder.build(List.of(), "q");
        assertThat(p).contains("无历史");
    }

    @Test
    void build_nullQuery_doesNotCrash() {
        // 防御：query 可能为 null（上游未标准化），prompt 构造永不崩
        String p = builder.build(List.of(), null);
        assertThat(p).isNotBlank();
    }

    @Test
    void build_nullHistory_doesNotCrash() {
        String p = builder.build(null, "q");
        assertThat(p).isNotBlank();
    }

    /**
     * 高风险售后动作硬约束（[[business-tools-workflow-dag]] T4 真跑发现：模型识 refund 意图但
     * requires_workflow=false/risk=medium/fallback=tool_first→跨字段 invalid→converge 兜底 source=DET→
     * #135 跳工作流。prompt 须显式钉死 refund/return 的 workflow 标志，使模型产 LLM-source 合法候选）。
     */
    @Test
    void buildContainsHardConstraints_forHighRiskWorkflowIntents() {
        String p = builder.build(List.of(), "我要退款 ORD-003");
        assertThat(p).contains("高风险售后动作");
        assertThat(p).contains("refund_request / return_request");
        assertThat(p).contains("needs_rag=true");
        assertThat(p).contains("跨字段一致性");
    }

    /** one-shot 示例教模型产 refund_request 合法候选（全字段正确值），非靠 converge 兜底。 */
    @Test
    void buildContainsOneShotExample_teachingValidRefundCandidate() {
        String p = builder.build(List.of(), "我要退款 ORD-003");
        assertThat(p).contains("\"intent\":\"refund_request\"");
        assertThat(p).contains("\"requires_workflow\":true");
        assertThat(p).contains("\"required_tools\":[\"get_order_detail\"]");
    }

    @Test
    void build_containsAmbiguousAndSecondaryConstraints() {
        RoutePromptBuilder b = new RoutePromptBuilder(new RoutePlanBaselines());
        String prompt = b.build(null, "退款 ORD-001");
        assertThat(prompt).contains("ambiguous");
        assertThat(prompt).contains("secondary_intent");
        assertThat(prompt).contains("多个相互冲突或反复变更的意图");
    }

    @Test
    void build_withPendingIntent_injectsHint() {
        RoutePromptBuilder b = new RoutePromptBuilder(new RoutePlanBaselines());
        String withHint = b.build(null, "ORD-001", "refund_request");
        String without = b.build(null, "ORD-001");
        assertThat(withHint).contains("未完成的 refund_request 等待订单号");
        assertThat(without).doesNotContain("等待订单号");
    }
}
