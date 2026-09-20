package com.agentdemo007.capability.workflow;

import com.agentdemo007.capability.business.OrderRecord;
import com.agentdemo007.capability.business.PolicyFragment;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LlmAfterSaleEligibilityJudge} 单测（生产化定案·2026-09-19 用户裁决：政策知识 + 订单
 * 实时事实一并交给 Agent 裁决，退役硬编码窗口规则）。
 *
 * <p>覆盖：合法 JSON 三态解析（ELIGIBLE/INELIGIBLE 含客户话术/UNCERTAIN）/ Markdown 围栏与
 * 前后说明文字宽容 / INELIGIBLE 缺 customerMessage 拒收 / decision 越界拒收 / LLM 空回复、
 * 异常、输出不合法 → 全部降级 UNCERTAIN（fail-safe 到人工，绝不冒充业务驳回）/ LLM 未装配恒
 * UNCERTAIN / 提示词含实时事实与政策知识（一并交 Agent 的契约钉）。
 */
class LlmAfterSaleEligibilityJudgeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);

    private static AfterSaleEligibilityJudge.EligibilityInput input(PolicyFragment policy) {
        OrderRecord order = new OrderRecord("ORD-001", "10086",
                Instant.parse("2026-09-09T10:00:00Z"), "已签收",
                java.util.List.of("无线耳机"), new BigDecimal("299.00"));
        return new AfterSaleEligibilityJudge.EligibilityInput("REFUND", order, policy,
                "我要退款 ORD-001", "10086");
    }

    private static ChatLlmService llmReturning(String reply) {
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("资格裁决"))).thenReturn(reply);
        return llm;
    }

    @Test
    void eligibleVerdict_parsed() {
        ChatLlmService llm = llmReturning(
                "{\"decision\":\"ELIGIBLE\",\"basis\":\"订单已签收且在退款政策期限内\",\"customerMessage\":null}");
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        AfterSaleEligibilityJudge.Verdict v = judge.judge(input(new PolicyFragment("退款政策", "01-refund.md", true)));

        assertThat(v.decision()).isEqualTo(AfterSaleEligibilityJudge.Decision.ELIGIBLE);
        assertThat(v.basis()).contains("退款政策期限");
        assertThat(v.customerMessage()).isNull();
    }

    @Test
    void ineligibleVerdict_parsed_withCustomerMessage() {
        ChatLlmService llm = llmReturning("""
                根据所给材料裁决如下：
                {"decision":"INELIGIBLE","basis":"参与促销活动的商品不参与七天无理由退货，存在质量问题的除外",
                 "customerMessage":"您的订单已超过7天无理由期限，如遇质量问题可联系人工客服。"}""");
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        AfterSaleEligibilityJudge.Verdict v = judge.judge(input(new PolicyFragment("退货政策原文", "02-return.md", true)));

        assertThat(v.decision()).isEqualTo(AfterSaleEligibilityJudge.Decision.INELIGIBLE);
        assertThat(v.basis()).contains("七天无理由退货");
        assertThat(v.customerMessage()).contains("人工客服");
    }

    @Test
    void markdownFencedJson_tolerated() {
        ChatLlmService llm = llmReturning("```json\n{\"decision\":\"ELIGIBLE\",\"basis\":\"符合\"}\n```");
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        AfterSaleEligibilityJudge.Verdict v = judge.judge(input(new PolicyFragment("政策", "s.md", true)));

        assertThat(v.decision()).isEqualTo(AfterSaleEligibilityJudge.Decision.ELIGIBLE);
    }

    @Test
    void uncertainVerdict_parsed() {
        ChatLlmService llm = llmReturning(
                "{\"decision\":\"UNCERTAIN\",\"basis\":\"政策未覆盖该情形\"}");
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        AfterSaleEligibilityJudge.Verdict v = judge.judge(input(new PolicyFragment("政策", "s.md", true)));

        assertThat(v.decision()).isEqualTo(AfterSaleEligibilityJudge.Decision.UNCERTAIN);
        assertThat(v.customerMessage()).isNull();
    }

    @Test
    void ineligibleWithoutCustomerMessage_rejectedAsIllegal_fallsToUncertain() {
        // 驳回必须携带客户话术（缺字段=输出不合法→UNCERTAIN，绝不产出无话术的驳回）
        ChatLlmService llm = llmReturning("{\"decision\":\"INELIGIBLE\",\"basis\":\"超期\"}");
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        AfterSaleEligibilityJudge.Verdict v = judge.judge(input(new PolicyFragment("政策", "s.md", true)));

        assertThat(v.decision()).isEqualTo(AfterSaleEligibilityJudge.Decision.UNCERTAIN);
    }

    @Test
    void illegalDecisionValue_fallsToUncertain() {
        ChatLlmService llm = llmReturning("{\"decision\":\"MAYBE\",\"basis\":\"??\"}");
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        assertThat(judge.judge(input(new PolicyFragment("政策", "s.md", true))).decision())
                .isEqualTo(AfterSaleEligibilityJudge.Decision.UNCERTAIN);
    }

    @Test
    void llmNullReply_fallsToUncertain() {
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llmReturning(null), CLOCK);

        assertThat(judge.judge(input(new PolicyFragment("政策", "s.md", true))).decision())
                .isEqualTo(AfterSaleEligibilityJudge.Decision.UNCERTAIN);
    }

    @Test
    void llmThrows_fallsToUncertain() {
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("资格裁决")))
                .thenThrow(new IllegalStateException("网关不可用"));
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        AfterSaleEligibilityJudge.Verdict v = judge.judge(input(new PolicyFragment("政策", "s.md", true)));

        assertThat(v.decision()).isEqualTo(AfterSaleEligibilityJudge.Decision.UNCERTAIN);
        assertThat(v.basis()).contains("异常");
    }

    @Test
    void llmAbsent_fallsToUncertain() {
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(null, CLOCK);

        assertThat(judge.judge(input(new PolicyFragment("政策", "s.md", true))).decision())
                .isEqualTo(AfterSaleEligibilityJudge.Decision.UNCERTAIN);
    }

    @Test
    void fallbackPolicyText_treatedAsNoKnowledge() {
        // 兜底政策（hit=false）不冒充知识：提示词须明确"按 UNCERTAIN 处理"而非把兜底话术当政策原文
        StringBuilder captured = new StringBuilder();
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("资格裁决"))).thenAnswer(inv -> {
            captured.append((String) inv.getArgument(0));
            return "{\"decision\":\"UNCERTAIN\",\"basis\":\"政策未覆盖\"}";
        });
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        judge.judge(input(new PolicyFragment("暂无相关政策信息，建议联系人工客服确认。", "兜底政策", false)));

        assertThat(captured.toString()).contains("政策库暂无该动作相关条目").contains("UNCERTAIN");
    }

    @Test
    void prompt_carriesRealtimeFactsAndKnowledge_andCurrentDate() {
        // 契约钉：订单实时事实（状态/下单时间/金额）+ 政策知识 + 当前日期，一并交给 Agent
        StringBuilder captured = new StringBuilder();
        ChatLlmService llm = mock(ChatLlmService.class);
        when(llm.chatRaw(anyString(), any(Intent.class), eq("资格裁决"))).thenAnswer(inv -> {
            captured.append((String) inv.getArgument(0));
            return "{\"decision\":\"ELIGIBLE\",\"basis\":\"ok\"}";
        });
        LlmAfterSaleEligibilityJudge judge = new LlmAfterSaleEligibilityJudge(llm, CLOCK);

        judge.judge(input(new PolicyFragment("退款政策原文片段", "01-refund.md", true)));

        String prompt = captured.toString();
        assertThat(prompt).contains("ORD-001").contains("已签收").contains("2026-09-09")
                .contains("299.00").contains("退款政策原文片段")
                .contains("2026-09-19"); // 当前日期（Clock 注入）
    }
}
