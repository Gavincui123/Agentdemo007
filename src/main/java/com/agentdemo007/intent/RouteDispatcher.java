package com.agentdemo007.intent;

import com.agentdemo007.gateway.config.RouteRule;

/**
 * 路由分发（第三层·意图→四类模型路由，§5.3.3）。
 *
 * <p>把识别出的 {@link Intent} 映射到 {@link RouteRule.RouteType}，供 {@link ModelRouter} 选模型：
 * <ul>
 *   <li>闲聊/简单QA → {@link RouteRule.RouteType#SIMPLE}（低成本快模型）</li>
 *   <li>推理/分析 → {@link RouteRule.RouteType#REASONING}（高能力模型）</li>
 *   <li>长上下文/复杂 → {@link RouteRule.RouteType#LONG_CONTEXT}（大窗口模型）</li>
 *   <li>结构化抽取 → {@link RouteRule.RouteType#STRUCTURED}（强结构化输出模型）</li>
 *   <li>转人工/未知/注入 → {@link RouteRule.RouteType#SIMPLE}（安全默认；注入实际在识别步短路，不到此）</li>
 * </ul>
 */
public class RouteDispatcher {

    public RouteRule.RouteType dispatch(Intent intent) {
        if (intent == null) {
            return RouteRule.RouteType.SIMPLE;
        }
        return switch (intent) {
            case REASONING -> RouteRule.RouteType.REASONING;
            case LONG_CONTEXT -> RouteRule.RouteType.LONG_CONTEXT;
            case STRUCTURED_EXTRACTION -> RouteRule.RouteType.STRUCTURED;
            case CHIT_CHAT, TRANSFER_TO_HUMAN, OTHER, INJECTION -> RouteRule.RouteType.SIMPLE;
        };
    }
}
