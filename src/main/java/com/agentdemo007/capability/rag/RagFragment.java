package com.agentdemo007.capability.rag;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * RAG 纯净片段（第四层·RAG 链路产出，能力层内部强类型载体）。
 *
 * <p>携带片段文本、相似度分数、来源标识，以及 Phase 20 时效字段
 * ({@code timestamp}/{@code validUntil}/{@code temporalTag})——强类型收口（④，非 Map）。
 * 链路内（Embedding→检索→校验→重排→注入扫描）流转本类型；最终经 {@link #displayText()} 抽取为
 * {@code List<String>} 写入 {@code PipelineContext.ragFragments}（客观数据层消费，§5.14 收口——
 * 不外泄 bespoke 结构到步骤间，仅文本 + 时效标注）。
 *
 * <p>时效治理（§5.4.1）：历史片段（{@code temporalTag=HISTORICAL}）经 {@link #displayText()}
 * 前缀"【历史参考资料·截至{date}】"隔离标注，过时不冒充当前；当前/无标注片段原文输出。
 * {@code timestamp}=片段 authored/索引时间，{@code validUntil}=失效时间（标注"截至"用）。
 *
 * @param text        片段文本（已过注入扫描）
 * @param score       相似度/重排分数（检索或重排阶段填充）
 * @param source      来源标识（文档 id/路径，审计可追溯）
 * @param timestamp   片段时间戳（authored/索引时间，可空）
 * @param validUntil  失效时间（过此时间视为历史，可空）
 * @param temporalTag 时效标注（"HISTORICAL"/"CURRENT"/null；HISTORICAL 触发隔离标注 + 重排时效衰减 T91）
 */
public record RagFragment(String text, double score, String source,
                           Instant timestamp, Instant validUntil, String temporalTag) {

    /** 既有 3 参构造（temporal 字段缺省 null，既有调用方零改动）。 */
    public RagFragment(String text, double score, String source) {
        this(text, score, source, null, null, null);
    }

    /**
     * 展示文本（RAG→文本抽取边界，RagStep 调用）：按时效标注隔离历史片段，过时不冒充当前。
     *
     * @return 历史片段前缀"【历史参考资料·截至{date}】"隔离头 + 原文；当前/无标注 → 原文
     */
    public String displayText() {
        if (!"HISTORICAL".equals(temporalTag)) {
            return text;
        }
        Instant labelTime = (validUntil != null) ? validUntil : timestamp;
        if (labelTime == null) {
            return "【历史参考资料】\n" + text;
        }
        String date = LocalDate.ofInstant(labelTime, ZoneOffset.UTC).toString();
        return "【历史参考资料·截至" + date + "】\n" + text;
    }
}
