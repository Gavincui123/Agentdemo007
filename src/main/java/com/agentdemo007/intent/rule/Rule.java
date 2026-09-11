package com.agentdemo007.intent.rule;

import com.agentdemo007.intent.IntentCategory;
import com.agentdemo007.session.model.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * 意图识别规则（第三层·规则前置层的策略接口）。
 *
 * <p>实现：{@link KeywordRule}（关键词命中）、{@link InjectionPatternRule}（注入模式命中）。
 * 由 {@link RuleMatcher} 聚合所有规则的命中结果做冲突仲裁（§5.3.1 规则前置）。
 *
 * <p>返回 {@code Optional.empty()} 表示本规则未命中。规则不调用 LLM——规则层是零 LLM 的快路径，
 * 注入尤其如此（注入路径零 LLM，§5.11）。
 */
public interface Rule {

    /**
     * 尝试匹配。
     *
     * @param query   标准化 Query 文本
     * @param history 会话历史（供需要上下文的规则参考）
     * @return 命中则返回分类结果，否则 empty
     */
    Optional<IntentCategory> match(String query, List<ChatMessage> history);
}
