package com.agentdemo007.capability.rag;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索校验器（第四层·相关性阈值 + 数量下限）。
 *
 * <p>两道闸门：① 剔除相似度低于 {@code minScore} 的低相关片段；② 若存活数量不足 {@code minCount}，
 * 视为召回不可靠 → 返回空，由上层降级为 {@code Degrade(RAG_SKIP)}（§5.12 RAG 行、deg-004
 * "向量库空召回"）。避免把零散低质片段塞入上下文误导生成。
 */
public class RetrievalValidator {

    private final double minScore;
    private final int minCount;

    public RetrievalValidator(double minScore, int minCount) {
        this.minScore = minScore;
        this.minCount = minCount;
    }

    /**
     * 校验召回结果。
     *
     * @param fragments 检索原始片段（按分数降序）
     * @return 过阈值且数量达标的片段；否则空
     */
    public List<RagFragment> validate(List<RagFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        List<RagFragment> kept = fragments.stream()
                .filter(f -> f.score() >= minScore)
                .collect(Collectors.toList());
        if (kept.size() < minCount) {
            return List.of(); // 召回不足，整段跳过 RAG
        }
        return kept;
    }
}
