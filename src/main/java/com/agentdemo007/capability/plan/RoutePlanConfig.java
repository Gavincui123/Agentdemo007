package com.agentdemo007.capability.plan;

import com.agentdemo007.gateway.llm.ChatLlmService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RoutePlan 子系统 Spring 装配（#134·Sub-slice 5·[[routeplan-design]]）。
 *
 * <p>装配 RoutePlan 决策链的 7 个 bean：baselines（11+1 基线）→ contractValidator（4 跨字段约束）
 * → ruleMatcher（5 政策约束收敛）+ intentRouteMapper（7→12 占位映射）+ promptBuilder（route prompt）
 * → routeCandidateParser（Jackson3 snake_case）+ routeCandidateSource（route_model decide+parse）
 * → routePlanner（rule→LLM→rule 兜底）。{@link RoutePlanStep}（@Order(605)）由 @Component 自动扫描，
 * 构造注入这三者。
 *
 * <p><b>无属性门控</b>（区别于 {@code LlmConfig} 仅 {@code llm.enabled=true} 激活）：RoutePlan 在 dev
 * noop 模式也跑通——{@link ChatLlmService#decide} 经 {@code NoopModelExecutor} 返回占位非 JSON →
 * {@link RouteCandidateParser} empty → {@link RoutePlanner} rule 兜底 general_chat（无真实模型/hang）。
 * prod（llm.enabled=true）则 decide 打真 route_model，产经收敛的真实候选。两条路同一装配，属性决定 decide 质量。
 *
 * <p>②降级语义（[[degradation-and-eval-principles]]）：装配层不引入失败路径——任一 bean 构造失败属
 * 启动期硬错（fail-fast），运行期降级在 {@link RoutePlanner}/{@link LlmRouteCandidateSource} 内（route 永不崩）。
 */
@Configuration
public class RoutePlanConfig {

    @Bean
    RoutePlanBaselines routePlanBaselines() {
        return new RoutePlanBaselines();
    }

    @Bean
    RoutePlanContractValidator routePlanContractValidator() {
        return new RoutePlanContractValidator();
    }

    @Bean
    RoutePlanRuleMatcher routePlanRuleMatcher(RoutePlanContractValidator validator, RoutePlanBaselines baselines) {
        return new RoutePlanRuleMatcher(validator, baselines);
    }

    @Bean
    IntentRouteMapper intentRouteMapper() {
        return new IntentRouteMapper();
    }

    @Bean
    RoutePromptBuilder routePromptBuilder(RoutePlanBaselines baselines) {
        return new RoutePromptBuilder(baselines);
    }

    @Bean
    RouteCandidateParser routeCandidateParser() {
        return RouteCandidateParser.create();
    }

    /**
     * route_model 候选来源（seam 接口类型注册，便于 {@link RoutePlanner} 按接口注入；
     * 唯一实现 {@link LlmRouteCandidateSource}，无 {@code @ConditionalOnMissingBean} 避 bean-override 竞态，
     * 同 [[phase21-hybrid-retrieval-design]] 属性门控互斥原则）。
     */
    @Bean
    RouteCandidateSource routeCandidateSource(ChatLlmService llm, RouteCandidateParser parser) {
        return new LlmRouteCandidateSource(llm, parser);
    }

    @Bean
    RoutePlanner routePlanner(RoutePlanRuleMatcher matcher, RoutePlanBaselines baselines,
                              RouteCandidateSource source) {
        return new RoutePlanner(matcher, baselines, source);
    }
}
