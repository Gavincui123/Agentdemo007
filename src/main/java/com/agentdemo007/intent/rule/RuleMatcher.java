package com.agentdemo007.intent.rule;

import com.agentdemo007.intent.Intent;
import com.agentdemo007.intent.IntentCategory;
import com.agentdemo007.session.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 规则匹配器 + 冲突仲裁（第三层·规则前置层的收口）。
 *
 * <p>聚合所有 {@link Rule} 的命中结果，按 §5.3.1 仲裁：
 * <ul>
 *   <li>任一规则命中 {@link Intent#INJECTION} → 注入胜出（优先级最高，零 LLM）；</li>
 *   <li>所有命中一致同一意图 → 取该意图（置信度取最大）；</li>
 *   <li>命中跨意图（冲突）或无命中 → 返回 {@code empty}，上交小模型识别（升级）。</li>
 * </ul>
 *
 * <p>返回 {@code Optional.empty()} = 规则层无法定论（冲突/无命中），由 {@code IntentRecognizerImpl}
 * 升级到小模型/大模型兜底。规则层永不调用 LLM。
 */
public class RuleMatcher {

    private final List<Rule> rules;

    public RuleMatcher(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * 匹配并仲裁。
     *
     * @return 规则层定论（含注入胜出/一致共识）；empty 表示需升级到模型层
     */
    public Optional<IntentCategory> match(String query, List<ChatMessage> history) {
        List<IntentCategory> hits = new ArrayList<>();
        for (Rule rule : rules) {
            rule.match(query, history).ifPresent(hits::add);
        }
        if (hits.isEmpty()) {
            return Optional.empty(); // 无命中 → 升级
        }
        // 注入胜出
        for (IntentCategory c : hits) {
            if (c.intent() == Intent.INJECTION) {
                return Optional.of(c);
            }
        }
        // 一致共识判定
        Intent first = hits.get(0).intent();
        boolean allAgree = true;
        double maxConf = hits.get(0).confidence();
        for (IntentCategory c : hits) {
            if (c.intent() != first) {
                allAgree = false;
            }
            if (c.confidence() > maxConf) {
                maxConf = c.confidence();
            }
        }
        if (allAgree) {
            return Optional.of(new IntentCategory(first, maxConf));
        }
        return Optional.empty(); // 冲突 → 升级
    }

    public List<Rule> rules() {
        return rules;
    }
}
