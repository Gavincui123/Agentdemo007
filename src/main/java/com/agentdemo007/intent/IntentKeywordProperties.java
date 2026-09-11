package com.agentdemo007.intent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code intent.keywords.*} 配置绑定（第三层·关键词前置分诊的配置化，Phase 7）。
 *
 * <p>把 Nacos/application.yml 的关键词规则绑定为 POJO，由 {@link IntentConfig} 翻成 {@code KeywordRule}。
 * 配置 {@code rules} 非空时<b>替换</b>内置默认关键词表（运维拥有完整关键词列表）；缺省/空时回落
 * 内置 11 条默认（闲聊/推理/长文/结构化/转人工）。注入模式 {@code InjectionPatternRule} 恒内置、
 * 不配置化（安全：注入词表不应被随意增删）。
 *
 * <pre>
 * intent:
 *   keywords:
 *     rules:
 *       - keyword: 你好
 *         intent: CHIT_CHAT
 *         confidence: 0.85
 *       - keyword: 分析
 *         intent: REASONING
 *         confidence: 0.9
 * </pre>
 *
 * <p>关键词表经此配置化后，可在 Nacos 热更新增删关键词而无需改代码——此前关键词写死于
 * {@link IntentConfig}、用户无法配置的痛点（"第一层关键词识别做了什么/怎么配"）由此收口。
 */
@ConfigurationProperties(prefix = "intent.keywords")
public class IntentKeywordProperties {

    private List<RuleDef> rules = new ArrayList<>();

    public List<RuleDef> getRules() {
        return rules;
    }

    public void setRules(List<RuleDef> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    /** 单条关键词规则：关键词 + 意图枚举 + 置信度（缺省 0.85）。 */
    public static class RuleDef {

        private String keyword;
        private Intent intent;
        private double confidence = 0.85;

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }

        public Intent getIntent() {
            return intent;
        }

        public void setIntent(Intent intent) {
            this.intent = intent;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }
    }
}
