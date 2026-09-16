package com.agentdemo007.capability.plan;

import com.agentdemo007.intent.Intent;

/**
 * 7 认知 Intent → 12 业务 fallbackIntent 占位映射（#134·{@link RoutePlanner} 的 fallbackIntent 来源）。
 *
 * <p>{@link com.agentdemo007.intent.IntentRecognitionStep}（@500）产出 7 认知 Intent，本映射器翻成
 * 12 业务意图 String（{@link RoutePlanner}/{@link RoutePlanBaselines} 的查询键）。
 *
 * <p><b>占位性质</b>（[[routeplan-design]] 缺口优先级③「12-intent 对齐」延后，blast radius 大）：
 * <ul>
 *   <li>三类确定性意图（闲聊/注入/转人工）直映确定性基线 → {@link RoutePlanner} rule 短路零 LLM（话术短路）；</li>
 *   <li>其余认知意图（推理/长上下文/结构化抽取/未知）→ {@code null} = 交 route_model 决策
 *       （RoutePlanner 不短路，LLM 救回真实 12 意图，LLM 挂则 general_chat 兜底）。</li>
 * </ul>
 *
 * <p>全量 7→12 枚举 replace 后，本映射器整体退役（RoutePlanStep 直接消费 12 意图枚举）。
 */
public class IntentRouteMapper {

    /**
     * 7 认知 Intent → 12 业务 fallbackIntent（null = route via LLM route_model，backstop general_chat）。
     *
     * @param intent 7 认知 Intent（{@link IntentRecognitionStep} 产出）；null 安全返回 null
     * @return 12 业务意图 String，或 null（交 route_model 决策）
     */
    public String fallbackIntent(Intent intent) {
        if (intent == null) return null;
        return switch (intent) {
            case CHIT_CHAT -> "general_chat";
            case INJECTION -> "security_request";
            case TRANSFER_TO_HUMAN -> "degradation_request";
            case REASONING, LONG_CONTEXT, STRUCTURED_EXTRACTION, OTHER -> null;
        };
    }
}
