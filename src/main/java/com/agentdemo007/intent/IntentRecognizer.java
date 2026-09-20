package com.agentdemo007.intent;

import com.agentdemo007.session.model.ChatMessage;

import java.util.List;

/**
 * 多层级意图识别接口（第三层·意图识别的入口契约）。
 *
 * <p>实现 {@link IntentRecognizerImpl} 采用多层级策略（§5.3.1）：
 * 规则前置 → 小模型分类 → 兜底；注入在规则层即拦截，零 LLM（§5.11）。
 * 对外仅返回 {@link IntentCategory}（路由侧只取意图枚举，§5.3.4 不暴露内部置信度给下游）。
 */
public interface IntentRecognizer {

    /**
     * 识别意图（规则层与模型层同源文本）。
     *
     * @param query   标准化 Query 文本
     * @param history 会话历史（供需要上下文的规则/模型参考）
     * @return 意图分类结果（含置信度；模型不可用时返回 {@link IntentCategory#unknown()}）
     */
    IntentCategory recognize(String query, List<ChatMessage> history);

    /**
     * 识别意图（规则层与模型层分源）。
     *
     * <p><b>规则层严禁喂改写产物</b>（2026-09-17 实测事故）：改写器会把历史语境内联进标准查询
     * （如「我要退货」→「我要退货（针对之前提到的退款订单，请提供订单号…）」），LLM 产物过
     * contains 词表会误命中内置业务词（「订单」）→ CHIT_CHAT 快路径 → 高风险退货绕过
     * route_model 风险收敛。词表按<b>用户原始输入</b>校准，规则层恒用 {@code rulesQuery=rawInput}；
     * 模型分类用改写后的自足 {@code classifyQuery}（指代已消解，分类质量更高）。
     *
     * @param rulesQuery    规则层匹配文本（调用方恒传 rawInput）
     * @param classifyQuery 模型分类文本（改写后的标准 Query）
     * @param history       会话历史
     * @return 意图分类结果（含置信度；模型不可用时返回 {@link IntentCategory#unknown()}）
     */
    IntentCategory recognize(String rulesQuery, String classifyQuery, List<ChatMessage> history);
}
