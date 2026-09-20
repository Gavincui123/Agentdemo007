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
     * 关键词规则引擎装配（配置化 + 内置兜底合并）。
     *
     * <p><b>合并模式</b>（非替换）：内置默认关键词（含业务查询词）<b>始终保留</b>——配置关键词同字
     * 覆盖内置（改意图/置信度），配置新增关键词追加。此前"配置非空→替换全部"会导致 Nacos 3 条
     * 配置覆盖 18 条内置→"订单"丢失→LLM 误分类 STRUCTURED_EXTRACTION→siliconflow-large 35s。
     * 合并后运维可增/改但不能意外删掉业务关键词。注入模式恒内置追加（安全：不配置化）。
     */
    @Bean
    RuleMatcher ruleMatcher(IntentKeywordProperties props) {
        // 1. 内置默认关键词（始终保留——业务关键词是性能兜底，配置不应意外删除）
        java.util.Map<String, KeywordRule> builtins = new java.util.LinkedHashMap<>();
        putBuiltin(builtins, "闲聊", Intent.CHIT_CHAT, 0.9);
        putBuiltin(builtins, "你好", Intent.CHIT_CHAT, 0.85);
        // 业务查询关键词 → CHIT_CHAT（小模型快回复）：工具/RAG 已取数据，小模型足够格式化回复；
        // 不升 REASONING（大模型 35s 延迟）。仅 LOW 风险业务词走此快路径（风险分层，[[routeplan-design]]）。
        // 退款/退货属高风险操作（refund_request/return_request 基线 HIGH+WORKFLOW_FIRST），已从词表移除：
        // 词表 contains 匹配无法区分「我要退款」（请求/HIGH）与「退款政策是什么」（咨询/faq_query/LOW），
        // 判断交给 LLM 理解层（改写→意图→route_model）→ risk_floor/workflow_boundary 收敛生效；
        // route_model 不可用时按 fallbackIntent/general_chat 基线兜底（原有降级策略不变）。
        putBuiltin(builtins, "订单", Intent.CHIT_CHAT, 0.8);
        putBuiltin(builtins, "物流", Intent.CHIT_CHAT, 0.8);
        putBuiltin(builtins, "商品", Intent.CHIT_CHAT, 0.75);
        putBuiltin(builtins, "优惠", Intent.CHIT_CHAT, 0.75);
        putBuiltin(builtins, "促销", Intent.CHIT_CHAT, 0.75);
        putBuiltin(builtins, "分析", Intent.REASONING, 0.9);
        putBuiltin(builtins, "推理", Intent.REASONING, 0.9);
        putBuiltin(builtins, "计算", Intent.REASONING, 0.85);
        putBuiltin(builtins, "长文", Intent.LONG_CONTEXT, 0.9);
        putBuiltin(builtins, "总结", Intent.LONG_CONTEXT, 0.85);
        putBuiltin(builtins, "抽取", Intent.STRUCTURED_EXTRACTION, 0.9);
        putBuiltin(builtins, "结构化", Intent.STRUCTURED_EXTRACTION, 0.9);
        putBuiltin(builtins, "转人工", Intent.TRANSFER_TO_HUMAN, 0.95);
        putBuiltin(builtins, "人工客服", Intent.TRANSFER_TO_HUMAN, 0.95);

        // 2. 配置关键词合并：同字覆盖（改意图/置信度），新增追加。
        // 高风险词护栏（类级防御，2026-09-17 定案）：退款/退货不得被词表直判 CHIT_CHAT——
        // 词表 contains 无法区分「我要退款」（请求/HIGH）与「退款政策是什么」（咨询/faq/LOW），
        // 且表在多处（内置/本地 yml/Nacos 热更），逐处打补丁必然"改了又错、改不完整"；
        // 故在唯一合并点拒绝装配并 WARN，判断一律交 LLM 理解层 + route_model 风险收敛
        // （route_model 不可用时 fallbackIntent/general_chat 兜底不变）。
        String source = "内置默认";
        List<IntentKeywordProperties.RuleDef> cfg = props.getRules();
        if (cfg != null && !cfg.isEmpty()) {
            source = "内置+配置合并";
            for (IntentKeywordProperties.RuleDef r : cfg) {
                if (r.getKeyword() == null || r.getKeyword().isBlank() || r.getIntent() == null) {
                    continue;
                }
                if (r.getIntent() == Intent.CHIT_CHAT && isHighRiskWord(r.getKeyword())) {
                    log.warn("配置关键词「{}」→CHIT_CHAT 命中高风险词护栏，拒绝装配（判断交 route_model）", r.getKeyword());
                    continue;
                }
                builtins.put(r.getKeyword().toLowerCase(java.util.Locale.ROOT),
                        new KeywordRule(r.getKeyword(), r.getIntent(), r.getConfidence()));
            }
        }

        List<Rule> rules = new ArrayList<>(builtins.values());
        int keywordCount = rules.size();
        // 注入模式规则（恒内置，不配置化——安全：注入词表不应被随意增删；零 LLM，§5.11）
        rules.add(new InjectionPatternRule(List.of(
                "ignore previous", "ignore the above", "忽略上面指令", "忽略以上指令",
                "system prompt", "系统提示词", "jailbreak", "越狱", "扮演 dan")));
        log.info("意图规则引擎已装配：{} 条规则（关键词 {} 条，{}；含注入模式）",
                rules.size(), keywordCount, source);
        return new RuleMatcher(rules);
    }

    private static void putBuiltin(java.util.Map<String, KeywordRule> map,
                                   String keyword, Intent intent, double confidence) {
        map.put(keyword.toLowerCase(java.util.Locale.ROOT),
                new KeywordRule(keyword, intent, confidence));
    }

    /** 高风险词表（对应 RoutePlanBaselines HIGH 基线：refund_request/return_request）。新增高风险意图时同步扩。 */
    private static final List<String> HIGH_RISK_WORDS = List.of("退款", "退货");

    /** 关键词是否命中高风险词表（contains 语义，与 KeywordRule 匹配口径一致：防「无理由退货」等变体绕过）。 */
    private static boolean isHighRiskWord(String keyword) {
        String k = keyword.toLowerCase(java.util.Locale.ROOT);
        return HIGH_RISK_WORDS.stream().anyMatch(k::contains);
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
