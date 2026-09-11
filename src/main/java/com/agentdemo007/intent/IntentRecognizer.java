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
     * 识别意图。
     *
     * @param query   标准化 Query 文本
     * @param history 会话历史（供需要上下文的规则/模型参考）
     * @return 意图分类结果（含置信度；模型不可用时返回 {@link IntentCategory#unknown()}）
     */
    IntentCategory recognize(String query, List<ChatMessage> history);
}
