package com.agentdemo007.intent.rule;

import com.agentdemo007.intent.Intent;
import com.agentdemo007.intent.IntentCategory;
import com.agentdemo007.session.model.ChatMessage;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 关键词规则（第三层·规则前置层）。
 *
 * <p>标准化 Query 包含指定关键词（大小写不敏感）→ 返回对应意图 + 置信度；
 * 否则未命中。关键词表与置信度来自配置（Nacos 热更新，Phase 7 先内置默认）。
 *
 * <p>零 LLM：纯字符串匹配，是注入/小模型之前的快路径（§5.3.1 规则前置）。
 */
public class KeywordRule implements Rule {

    private final String keyword;
    private final Intent intent;
    private final double confidence;

    public KeywordRule(String keyword, Intent intent, double confidence) {
        this.keyword = keyword.toLowerCase(Locale.ROOT);
        this.intent = intent;
        this.confidence = confidence;
    }

    @Override
    public Optional<IntentCategory> match(String query, List<ChatMessage> history) {
        if (query == null) {
            return Optional.empty();
        }
        if (query.toLowerCase(Locale.ROOT).contains(keyword)) {
            return Optional.of(new IntentCategory(intent, confidence));
        }
        return Optional.empty();
    }

    public String keyword() {
        return keyword;
    }

    public Intent intent() {
        return intent;
    }
}
