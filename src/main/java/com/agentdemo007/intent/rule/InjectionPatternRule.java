package com.agentdemo007.intent.rule;

import com.agentdemo007.intent.Intent;
import com.agentdemo007.intent.IntentCategory;
import com.agentdemo007.session.model.ChatMessage;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 注入模式规则（第三层·规则前置层的安全快路径）。
 *
 * <p>标准化 Query 命中任一注入特征模式（大小写不敏感包含）→ 返回 {@link Intent#INJECTION}（置信 1.0），
 * 由 {@link RuleMatcher} 优先采纳（注入胜出，零 LLM，§5.11 注入路径零 LLM 调用）。
 *
 * <p>模式库来自配置（Nacos 热更新，§8 风险与回退：pattern 库 Nacos 热更新），Phase 7 先内置默认。
 */
public class InjectionPatternRule implements Rule {

    private final List<String> patterns;

    public InjectionPatternRule(List<String> patterns) {
        this.patterns = List.copyOf(patterns).stream()
                .map(p -> p.toLowerCase(Locale.ROOT))
                .toList();
    }

    @Override
    public Optional<IntentCategory> match(String query, List<ChatMessage> history) {
        if (query == null) {
            return Optional.empty();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            if (lower.contains(pattern)) {
                return Optional.of(new IntentCategory(Intent.INJECTION, 1.0));
            }
        }
        return Optional.empty();
    }

    public List<String> patterns() {
        return patterns;
    }
}
