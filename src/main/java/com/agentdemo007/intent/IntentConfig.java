package com.agentdemo007.intent;

import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.gateway.selector.ModelSelector;
import com.agentdemo007.intent.rule.InjectionPatternRule;
import com.agentdemo007.intent.rule.KeywordRule;
import com.agentdemo007.intent.rule.Rule;
import com.agentdemo007.intent.rule.RuleMatcher;
import com.agentdemo007.observability.AgentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图识别与模型路由装配（Phase 7·第三层）。
 *
 * <p>注册意图识别内核 bean：规则引擎（{@link RuleMatcher} 含默认关键词表 + 注入模式库）、
 * {@link IntentRecognizer}（规则→小模型→兜底）、{@link IntentClassifier}（置信度校验）、
 * {@link RouteDispatcher}（意图→四类路由）、{@link ModelRouter}（对接选择策略选模型）。
 * 两个 {@code @Order} 步骤（{@code IntentRecognitionStep}/{@code RouteDispatchStep}）已
 * 标注 {@code @Component}，自动收集进流水线。
 *
 * <p>关键词表、注入模式库、置信度阈值当前内置默认；后续由 Nacos 热更新覆盖
 * （§5.3 Nacos 配置关键词表/冲突阈值/注入模式/置信度阈值/路由规则——随 NacosModelConfigSource 接入）。
 */
@Configuration
@EnableConfigurationProperties(IntentKeywordProperties.class)
public class IntentConfig {

    private static final Logger log = LoggerFactory.getLogger(IntentConfig.class);

    /** 置信度放行阈值（低于则兜底 UNKNOWN 继续，§5.3.2）。 */
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    /**
     * 关键词规则引擎装配（配置化）。
     *
     * <p>{@code intent.keywords.rules} 非空 → 用配置<b>替换</b>内置默认（运维拥有完整关键词列表，
     * 可经 Nacos 增删）；缺省/空 → 回落内置 11 条默认。注入模式 {@link InjectionPatternRule}
     * 恒内置追加（安全：注入词表不应被随意增删）。
     */
    @Bean
    RuleMatcher ruleMatcher(IntentKeywordProperties props) {
        List<Rule> rules = new ArrayList<>();
        List<IntentKeywordProperties.RuleDef> cfg = props.getRules();
        String source;
        if (cfg != null && !cfg.isEmpty()) {
            source = "配置覆盖默认";
            for (IntentKeywordProperties.RuleDef r : cfg) {
                if (r.getKeyword() == null || r.getKeyword().isBlank() || r.getIntent() == null) {
                    continue; // 跳过非法条目
                }
                rules.add(new KeywordRule(r.getKeyword(), r.getIntent(), r.getConfidence()));
            }
        } else {
            source = "内置默认";
            rules.add(new KeywordRule("闲聊", Intent.CHIT_CHAT, 0.9));
            rules.add(new KeywordRule("你好", Intent.CHIT_CHAT, 0.85));
            // 业务查询关键词 → CHIT_CHAT（小模型快回复）：工具/RAG 已取数据，小模型足够格式化回复；
            // 不升 REASONING（大模型 35s 延迟）。退款/退货走工作流时 presetReply 短路不调 LLM，
            // 认知意图仅决定非工作流路径（如"退款政策是什么"）的模型。
            rules.add(new KeywordRule("订单", Intent.CHIT_CHAT, 0.8));
            rules.add(new KeywordRule("物流", Intent.CHIT_CHAT, 0.8));
            rules.add(new KeywordRule("商品", Intent.CHIT_CHAT, 0.75));
            rules.add(new KeywordRule("退款", Intent.CHIT_CHAT, 0.75));
            rules.add(new KeywordRule("退货", Intent.CHIT_CHAT, 0.75));
            rules.add(new KeywordRule("优惠", Intent.CHIT_CHAT, 0.75));
            rules.add(new KeywordRule("促销", Intent.CHIT_CHAT, 0.75));
            rules.add(new KeywordRule("分析", Intent.REASONING, 0.9));
            rules.add(new KeywordRule("推理", Intent.REASONING, 0.9));
            rules.add(new KeywordRule("计算", Intent.REASONING, 0.85));
            rules.add(new KeywordRule("长文", Intent.LONG_CONTEXT, 0.9));
            rules.add(new KeywordRule("总结", Intent.LONG_CONTEXT, 0.85));
            rules.add(new KeywordRule("抽取", Intent.STRUCTURED_EXTRACTION, 0.9));
            rules.add(new KeywordRule("结构化", Intent.STRUCTURED_EXTRACTION, 0.9));
            rules.add(new KeywordRule("转人工", Intent.TRANSFER_TO_HUMAN, 0.95));
            rules.add(new KeywordRule("人工客服", Intent.TRANSFER_TO_HUMAN, 0.95));
        }
        int keywordCount = rules.size();
        // 注入模式规则（恒内置，不配置化——安全：注入词表不应被随意增删；零 LLM，§5.11）
        rules.add(new InjectionPatternRule(List.of(
                "ignore previous", "ignore the above", "忽略上面指令", "忽略以上指令",
                "system prompt", "系统提示词", "jailbreak", "越狱", "扮演 dan")));
        log.info("意图规则引擎已装配：{} 条规则（关键词 {} 条，{}；含注入模式）",
                rules.size(), keywordCount, source);
        return new RuleMatcher(rules);
    }

    @Bean
    IntentRecognizer intentRecognizer(RuleMatcher ruleMatcher, ChatLlmService llm) {
        return new IntentRecognizerImpl(ruleMatcher, llm);
    }

    @Bean
    IntentClassifier intentClassifier() {
        return new IntentClassifier(CONFIDENCE_THRESHOLD);
    }

    @Bean
    RouteDispatcher routeDispatcher() {
        return new RouteDispatcher();
    }

    @Bean
    ModelRouter modelRouter(ModelConfigCenter center, ModelSelector selector, AgentMetrics metrics) {
        return new ModelRouter(center, selector, metrics);
    }
}
