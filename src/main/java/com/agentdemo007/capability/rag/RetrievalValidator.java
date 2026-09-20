package com.agentdemo007.capability.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索置信度终闸（第四层·检索链路<b>最终动作</b>：只有过闸知识才允许进入 LLM 上下文）。
 *
 * <p><b>双判据（score 与 relevance 分口径）</b>：
 * <ul>
 *   <li>被远程重排过的片段（{@code relevance != null}）：cross-encoder 相关度
 *       {@code relevance >= rerankMinScore}——强信号裁决，0.2~0.4 cosine 边际带候选由它甄别；</li>
 *   <li>未重排（跳过重排 / 降级 BM25 兜底重排）的片段：余弦检索置信度
 *       {@code cosineScored && score >= minScore}——BM25 词面分/关键词命中数（{@code cosineScored=false}）
 *       口径未校准，<b>不得凭它过 cosine 闸</b>：未重排时直接丢弃，经远程重排裁决后凭 relevance 入上下文。</li>
 * </ul>
 *
 * <p>数量下限：过闸片段不足 {@code minCount} → 返回空，上层降级 {@code Degrade(RAG_SKIP)}
 * （§5.12 RAG 行、deg-004）——低置信知识绝不进 LLM，宁可话术兜底。
 *
 * <p>观测：{@link #decide} 逐片段给出裁决与淘汰原因（score/relevance vs 阈值、无余弦口径），
 * 供 {@code RagStep} 漏斗日志把「为什么被筛掉」从黑盒变白盒；{@link #validate} 与其同源判定。
 */
public class RetrievalValidator {

    /**
     * 单片段终闸裁决记录（观测用）：{@code fragment} + {@code passed} + {@code reason}
     * （淘汰原因含判据口径与阈值，如「余弦低于阈值 score=0.25&lt;0.4」）。
     */
    public record GateRecord(RagFragment fragment, boolean passed, String reason) {
    }

    private final double minScore;
    private final int minCount;
    private final double rerankMinScore;

    /** 兼容旧 2 参构造（重排阈值缺省与余弦阈值同值；既有测试调用方零改动）。 */
    public RetrievalValidator(double minScore, int minCount) {
        this(minScore, minCount, minScore);
    }

    public RetrievalValidator(double minScore, int minCount, double rerankMinScore) {
        this.minScore = minScore;
        this.minCount = minCount;
        this.rerankMinScore = rerankMinScore;
    }

    /**
     * 逐片段终闸裁决（观测口径）：每个候选给出过闸与否及原因。与 {@link #validate} 同源判定
     * （validate = 本方法 passed 子集 + 数量下限），日志与实际闸门行为不漂移。
     */
    public List<GateRecord> decide(List<RagFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        List<GateRecord> records = new ArrayList<>(fragments.size());
        for (RagFragment f : fragments) {
            if (f == null) {
                continue;
            }
            if (f.relevance() != null) {
                boolean passed = f.relevance() >= rerankMinScore;
                records.add(new GateRecord(f, passed, passed
                        ? String.format("重排裁决通过 relevance=%.3f>=%.2f", f.relevance(), rerankMinScore)
                        : String.format("重排裁决淘汰 relevance=%.3f<%.2f", f.relevance(), rerankMinScore)));
            } else if (!f.cosineScored()) {
                records.add(new GateRecord(f, false, "无余弦口径（BM25-only）未经理裁决，不得入上下文"));
            } else if (f.score() >= minScore) {
                records.add(new GateRecord(f, true,
                        String.format("余弦口径通过 score=%.3f>=%.2f", f.score(), minScore)));
            } else {
                records.add(new GateRecord(f, false,
                        String.format("余弦低于阈值 score=%.3f<%.2f", f.score(), minScore)));
            }
        }
        return records;
    }

    /**
     * 置信度终闸：双判据过滤 + 数量下限。
     *
     * @param fragments 候选池（宽召回/粗滤/重排后，按链路序）
     * @return 过闸且数量达标的片段；否则空（上层 RAG_SKIP）
     */
    public List<RagFragment> validate(List<RagFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        List<RagFragment> kept = decide(fragments).stream()
                .filter(GateRecord::passed)
                .map(GateRecord::fragment)
                .collect(Collectors.toList());
        if (kept.size() < minCount) {
            return List.of(); // 召回不足/全为低置信，整段跳过 RAG
        }
        return kept;
    }
}
