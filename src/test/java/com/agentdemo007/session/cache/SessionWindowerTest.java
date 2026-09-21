package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 原文窗口策略测试（Phase 22·T99 字符启发式轨）。
 *
 * <p>钉死：按轮取窗不拆 User/Ai 对、预算内多轮保留、最后一轮恒保留（宁多喂不空喂）、
 * 空/遗留乱序值安全退化。
 */
class SessionWindowerTest {

    @Test
    void window_allTurnsFit_keepsEverything() {
        SessionWindower w = new SessionWindower(100, 1.0); // 预算 100 字符
        List<ChatMessage> msgs = List.of(
                user("一"), ai("答一"), user("二"), ai("答二")); // 共 6 字符

        assertThat(w.window(msgs)).isSameAs(msgs);
    }

    @Test
    void window_overBudget_cutsAtTurnBoundary_neverSplitsPair() {
        SessionWindower w = new SessionWindower(10, 1.0); // 预算 10 字符
        List<ChatMessage> msgs = List.of(
                user("第一轮很长的问题一二三四五"),   // 12
                ai("第一轮很长的回答一二三四五"),     // 12
                user("短问"),                        // 2
                ai("短答"));                         // 2
        // 最后一轮 4 字符装得下；倒数第二轮 24 字符装不下 → 裁到轮起点（index 2）

        List<ChatMessage> window = w.window(msgs);

        assertThat(window).hasSize(2);
        assertThat(window.get(0).content()).isEqualTo("短问");
        assertThat(window.get(1).content()).isEqualTo("短答");
    }

    @Test
    void window_lastTurnAloneOverBudget_keepsItAnyway() {
        // 宁多喂不空喂：最后一轮自身超预算 → 整轮保留（绝不裁出半轮/空窗口）
        SessionWindower w = new SessionWindower(5, 1.0);
        List<ChatMessage> msgs = List.of(
                user("旧问一"), ai("旧答一"),
                user("超长最后问题一二三四五六七八九十"), ai("超长最后回答一二三四五六七八九十"));

        List<ChatMessage> window = w.window(msgs);

        assertThat(window).hasSize(2);
        assertThat(window.get(0).content()).contains("超长最后问题");
        assertThat(window.get(1).content()).contains("超长最后回答");
    }

    @Test
    void window_empty_returnsEmpty() {
        assertThat(new SessionWindower(10, 2.0).window(List.of())).isEmpty();
    }

    @Test
    void window_legacyUnpairedTail_degeneratesToKeepAll() {
        // 存量遗留值不成对（单条 Ai 收尾，无轮起点可识别）→ 全保留（不破坏对结构）
        SessionWindower w = new SessionWindower(2, 1.0);
        List<ChatMessage> msgs = List.of(user("一二三四五六七八九十"));

        assertThat(w.window(msgs)).isSameAs(msgs);
    }

    @Test
    void windowBudgetChars_multipliesTokensByCharsPerToken() {
        assertThat(new SessionWindower(100, 2.0).windowBudgetChars()).isEqualTo(200);
        assertThat(new SessionWindower(100, 0).windowBudgetChars()).isEqualTo(200); // 非法 charsPerToken 收敛 2.0
    }

    private static ChatMessage.User user(String c) {
        return new ChatMessage.User(c);
    }

    private static ChatMessage.Ai ai(String c) {
        return new ChatMessage.Ai(c);
    }
}
