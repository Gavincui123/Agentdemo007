package com.agentdemo007.capability.rag;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * RAG 纯净片段（第四层·RAG 链路产出，能力层内部强类型载体）。
 *
 * <p>携带片段文本、相似度分数、来源标识，以及 Phase 20 时效字段
 * ({@code timestamp}/{@code validUntil}/{@code temporalTag})——强类型收口（④，非 Map）。
 * 链路内（Embedding→检索→粗滤→重排→置信度终闸→注入扫描）流转本类型；最终经
 * {@link #displayText()} 抽取为 {@code List<String>} 写入 {@code context.ragFragments}
 * （客观数据层消费，§5.14 收口）。
 *
 * <p>时效治理（§5.4.1）：历史片段（{@code temporalTag=HISTORICAL}）经 {@link #displayText()}
 * 前缀"【历史参考资料·截至{date}】"隔离标注，过时不冒充当前；当前/无标注片段原文输出。
 *
 * <p>真库扩展（真实 RAG·Chroma）：
 * <ul>
 *   <li>{@code domain} — 知识域（入库 metadata.domain 透传，如 after_sale_policy/faq），供
 *       RoutePlan.knowledgeDomains 窄化与政策域过滤；dev 种子/旧调用为 null（窄化时恒通过）。</li>
 *   <li>{@code relevance} — 远程重排相关度（cross-encoder 0~1），<b>仅重排成功后非空</b>；
 *       本地/降级重排不写（分数口径未校准）。置信度终闸双判据：被重排的看本字段，未重排的看 {@code score}。</li>
 *   <li>{@code cosineScored} — {@code score} 是否为余弦检索置信度。true=稠密余弦（可过 cosine 阈值）；
 *       false=BM25 词面分/关键词命中数等非余弦口径——未重排时不得凭它过 cosine 终闸
 *       （低置信知识绝不进 LLM），只有被远程重排裁决后才可入上下文。</li>
 * </ul>
 *
 * @param text         片段文本（已过注入扫描）
 * @param score        检索置信度（余弦相似度或 BM25 分，看 {@code cosineScored} 口径）
 * @param source       来源标识（文档 id/路径，审计可追溯）
 * @param timestamp    片段时间戳（authored/索引时间，可空）
 * @param validUntil   失效时间（过此时间视为历史，可空）
 * @param temporalTag  时效标注（"HISTORICAL"/"CURRENT"/null；HISTORICAL 触发隔离标注 + 重排时效衰减 T91）
 * @param domain       知识域（真库 metadata，可空）
 * @param relevance    远程重排相关度（0~1；未重排为 null）
 * @param cosineScored score 是否为余弦口径（BM25/命中数为 false）
 */
public record RagFragment(String text, double score, String source,
                           Instant timestamp, Instant validUntil, String temporalTag,
                           String domain, Double relevance, boolean cosineScored) {

    /** 既有 3 参构造（真库扩展字段缺省：domain/relevance null、非余弦口径；既有调用方零改动）。 */
    public RagFragment(String text, double score, String source) {
        this(text, score, source, null, null, null, null, null, false);
    }

    /** 既有 6 参构造（时效字段齐、真库扩展字段缺省；种子语料/时效治理调用方零改动）。 */
    public RagFragment(String text, double score, String source,
                       Instant timestamp, Instant validUntil, String temporalTag) {
        this(text, score, source, timestamp, validUntil, temporalTag, null, null, false);
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
