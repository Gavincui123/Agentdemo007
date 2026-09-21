package com.agentdemo007.session.cache;

import com.agentdemo007.session.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * L1 原文窗口策略（Phase 22·T99 token 度量双轨的"字符启发式"轨）。
 *
 * <p>从会话消息尾部按轮取窗：轮 = User+Ai 对（终局按对追加）；从最后一轮向前逐轮累计
 * 字符数，装不下的更早轮裁掉——<b>只在轮边界裁剪，绝不拆散一对</b>；最后一轮恒保留
 * （即使其自身超预算——"至少有本轮"的降级口径，宁多喂不空喂）。
 *
 * <p>token 度量双轨分工：真实 usage 管<b>触发阈值</b>（终局读上轮主模型 usage），
 * 本类字符启发式（{@code charsPerToken}）管<b>窗口分轮</b>——确定性可测、零 tokenizer 依赖。
 * 无摘要（L2 未就绪）时读侧照常用本窗口，只是少了摘要段（竞态降级：多喂原文，不等待）。
 */
public class SessionWindower {

    private final int windowBudgetTokens;
    private final double charsPerToken;

    public SessionWindower(int windowBudgetTokens, double charsPerToken) {
        this.windowBudgetTokens = Math.max(1, windowBudgetTokens);
        this.charsPerToken = (charsPerToken > 0) ? charsPerToken : 2.0;
    }

    /** 历史窗口字符预算（tokens × charsPerToken）。 */
    public int windowBudgetChars() {
        return (int) Math.round(windowBudgetTokens * charsPerToken);
    }

    /**
     * 取最近原文窗口：尾部轮次字符累计 ≤ 预算；至少保留最后一轮。
     *
     * <p>轮起点 = 首条消息，或任一「User 紧随 Ai 之后」的位置（该 User 与其后 Ai 为一对，
     * 不可拆）。存量遗留值若不成对，退化为全保留（宁多喂不空喂，且不破坏对结构）。
     */
    public List<ChatMessage> window(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int n = messages.size();
        List<Integer> turnStarts = new ArrayList<>();
        turnStarts.add(0);
        for (int i = 1; i < n; i++) {
            if (isTurnBoundary(messages, i)) {
                turnStarts.add(i);
            }
        }
        int budget = windowBudgetChars();
        long total = 0;
        int cutFrom = 0;
        for (int k = turnStarts.size() - 1; k >= 0; k--) {
            int start = turnStarts.get(k);
            int end = (k + 1 < turnStarts.size()) ? turnStarts.get(k + 1) : n;
            for (int i = start; i < end; i++) {
                total += charsOf(messages.get(i));
            }
            if (total > budget && k < turnStarts.size() - 1) {
                cutFrom = turnStarts.get(k + 1); // 该轮装不下且非最后一轮 → 裁到它之前
                break;
            }
            // 最后一轮自身超预算 → 不裁（宁多喂不空喂）
        }
        return (cutFrom <= 0) ? messages : List.copyOf(messages.subList(cutFrom, n));
    }

    /** i 是否为轮起点（messages[i] 为 User 且前一条为 Ai——该对不可拆）。 */
    private static boolean isTurnBoundary(List<ChatMessage> messages, int i) {
        return i > 0
                && messages.get(i) instanceof ChatMessage.User
                && messages.get(i - 1) instanceof ChatMessage.Ai;
    }

    private static int charsOf(ChatMessage m) {
        String c = m.content();
        return (c != null) ? c.length() : 0;
    }
}
