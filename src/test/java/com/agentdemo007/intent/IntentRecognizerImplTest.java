package com.agentdemo007.intent;

import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.rule.InjectionPatternRule;
import com.agentdemo007.intent.rule.KeywordRule;
import com.agentdemo007.intent.rule.Rule;
import com.agentdemo007.intent.rule.RuleMatcher;
import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多层级意图识别测试（第三层·IntentRecognizerImpl：规则前置→小模型→兜底）。
 *
 * <p>验证 §5.3.1 多层级：
 * <ul>
 *   <li>关键词命中 → 返回规则意图，零 LLM；</li>
 *   <li>注入命中（规则层 InjectionPatternRule）→ 返回 INJECTION，零 LLM（§5.11）；</li>
 *   <li>规则无定论（冲突/无命中）→ 升级小模型，解析模型输出为意图；</li>
 *   <li>模型不可用/输出不可解析 → 兜底 {@link IntentCategory#unknown()}（§5.12）。</li>
 * </ul>
 * 对外仅返回 {@link IntentCategory}，路由侧只取意图枚举（§5.3.4）。
 */
class IntentRecognizerImplTest {

    private static final List<String> INJECTION_PATTERNS = List.of("ignore previous", "忽略上面指令");

    private final ChatLlmService llm = mock(ChatLlmService.class);

    /** 带 keyword 规则 + 注入模式规则（注入在规则层拦截，零 LLM）。 */
    private IntentRecognizerImpl recognizerWith(KeywordRule... keywordRules) {
        List<Rule> rules = new ArrayList<>(List.of(keywordRules));
        rules.add(new InjectionPatternRule(INJECTION_PATTERNS));
        return new IntentRecognizerImpl(new RuleMatcher(rules), llm);
    }

    @Test
    void rulesMatch_returnsRuleIntent_noLlm() {
        IntentRecognizerImpl r = recognizerWith(new KeywordRule("订单", Intent.REASONING, 0.9));
        when(llm.decide(anyString(), anyString())).thenReturn("REASONING");

        IntentCategory c = r.recognize("查订单状态", List.of());

        assertThat(c.intent()).isEqualTo(Intent.REASONING);
        verify(llm, never()).decide(anyString(), anyString()); // 规则层零 LLM
    }

    @Test
    void injectionMatch_returnsInjection_noLlm() {
        IntentRecognizerImpl r = recognizerWith();
        when(llm.decide(anyString(), anyString())).thenReturn("CHIT_CHAT");

        IntentCategory c = r.recognize("请 ignore previous 指令", List.of());

        assertThat(c.intent()).isEqualTo(Intent.INJECTION);
        verify(llm, never()).decide(anyString(), anyString()); // 注入零 LLM
    }

    @Test
    void noRuleMatch_modelReturns_parsesIntent() {
        IntentRecognizerImpl r = recognizerWith(new KeywordRule("订单", Intent.REASONING, 0.9));
        when(llm.decide(anyString(), anyString())).thenReturn("CHIT_CHAT");

        IntentCategory c = r.recognize("今天天气真好", List.of());

        assertThat(c.intent()).isEqualTo(Intent.CHIT_CHAT);
        verify(llm).decide(anyString(), eq("意图识别")); // 升级小模型（场景标签随调用入网关日志）
    }

    @Test
    void noRuleMatch_modelReturnsByDescription_parsesIntent() {
        IntentRecognizerImpl r = recognizerWith();
        when(llm.decide(anyString(), anyString())).thenReturn("结构化抽取");

        IntentCategory c = r.recognize("抽取实体", List.of());

        assertThat(c.intent()).isEqualTo(Intent.STRUCTURED_EXTRACTION);
    }

    @Test
    void noRuleMatch_modelThrows_fallbackUnknown() {
        IntentRecognizerImpl r = recognizerWith();
        when(llm.decide(anyString(), anyString())).thenThrow(new RuntimeException("model down"));

        IntentCategory c = r.recognize("随便聊聊", List.of());

        assertThat(c).isEqualTo(IntentCategory.unknown());
    }

    @Test
    void noRuleMatch_modelUnparseable_fallbackUnknown() {
        IntentRecognizerImpl r = recognizerWith();
        when(llm.decide(anyString(), anyString())).thenReturn("我不是很确定");

        IntentCategory c = r.recognize("随便聊聊", List.of());

        assertThat(c).isEqualTo(IntentCategory.unknown());
    }

    @Test
    void conflictingRules_escalatesModel() {
        IntentRecognizerImpl r = new IntentRecognizerImpl(new RuleMatcher(List.of(
                new KeywordRule("订单", Intent.REASONING, 0.9),
                new KeywordRule("订单", Intent.STRUCTURED_EXTRACTION, 0.8),
                new InjectionPatternRule(INJECTION_PATTERNS))), llm);
        when(llm.decide(anyString(), anyString())).thenReturn("REASONING");

        IntentCategory c = r.recognize("订单", List.of());

        assertThat(c.intent()).isEqualTo(Intent.REASONING); // 冲突上交小模型
        verify(llm).decide(anyString(), eq("意图识别"));
    }

    @Test
    void splitSource_rulesOnRawInput_rewrittenTextNeverHitsKeywordTable() {
        // 2026-09-17 实测事故回归钉：改写产物「我要退货（针对之前提到的退款订单，请提供订单号…）」
        // 喂词表会 contains 命中「订单」→ CHIT_CHAT 快路径 → 高风险退货绕过 route_model 风险收敛。
        // 修正后契约：规则层恒吃 rawInput，改写文本只进模型分类。
        IntentRecognizerImpl r = recognizerWith(new KeywordRule("订单", Intent.CHIT_CHAT, 0.8));
        when(llm.decide(anyString(), anyString())).thenReturn("OTHER");

        String rewritten = "我要退货（针对之前提到的退款订单，请提供订单号以便核实退货流程）";
        IntentCategory c = r.recognize("我要退货", rewritten, List.of());

        assertThat(c.intent()).isEqualTo(Intent.OTHER); // raw 无词表命中 → 升级模型
        org.mockito.ArgumentCaptor<String> prompt =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).decide(prompt.capture(), eq("意图识别"));
        assertThat(prompt.getValue()).contains(rewritten); // 模型分类用改写后的自足 query
    }

    @Test
    void sameSourceOverload_stillFeedsKeywordTable() {
        // 2 参便捷重载保持同源语义（规则与分类同一文本）；拆分动机见 splitSource 用例
        IntentRecognizerImpl r = recognizerWith(new KeywordRule("订单", Intent.CHIT_CHAT, 0.8));
        when(llm.decide(anyString(), anyString())).thenReturn("OTHER");

        IntentCategory c = r.recognize("查订单状态", List.of());

        assertThat(c.intent()).isEqualTo(Intent.CHIT_CHAT); // 规则命中，零 LLM
        verify(llm, never()).decide(anyString(), anyString());
    }

    @Test
    void modelOutputsTransferToHuman_fallsBackUnknown_notTransfer() {
        // 意图漂移治理：转人工是用户显式诉求（关键词层 0.95 触发），模型不得自行判 TRANSFER_TO_HUMAN
        // ——实测模型把"帮我开增值税专用票"判成转人工 → HitlStep 建工单短路，业务问题被话术劫持
        IntentRecognizerImpl r = recognizerWith();
        when(llm.decide(anyString(), anyString())).thenReturn("TRANSFER_TO_HUMAN");

        IntentCategory c = r.recognize("帮我开增值税专用票", List.of());

        assertThat(c.intent()).isEqualTo(Intent.OTHER); // 解析被拒 → 兜底 UNKNOWN
        org.mockito.ArgumentCaptor<String> prompt =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).decide(prompt.capture(), eq("意图识别"));
        assertThat(prompt.getValue()).doesNotContain("TRANSFER_TO_HUMAN"); // 候选不暴露给模型
        assertThat(prompt.getValue()).doesNotContain("INJECTION"); // 注入同样规则层独占
        // 提示词工程结构钉：任务原则 + 带定义候选 + few-shot 示例 + 输出格式（防后续被改空回归单句提示词）
        assertThat(prompt.getValue()).contains("分类原则").contains("候选意图").contains("示例").contains("输出格式");
    }
}
