package com.agentdemo007.capability.rag;

import java.util.Collection;

/**
 * 按来源删除向量 seam（[[kb-ingest-design]]·任务3 版本管理配套）。
 *
 * <p>知识库换版/下架时须把旧片段从<b>所有</b>检索通道移除（稠密 + 稀疏 BM25），否则
 * "检索只见最新版"不成立。实现方：{@code InMemoryVectorStore}（dev）、
 * {@code ChromaVectorStore}（真库稠密）、{@code LuceneBm25IndexService}（真库稀疏）。
 * 录入服务注入 {@code List<SourceDeletableStore>} 广播删除（best-effort，失败告警不阻塞——
 * Lucene 下次启动 syncFromChroma 会增量对齐）。
 */
public interface SourceDeletableStore {

    /**
     * 精确删除指定来源的向量/索引记录。
     *
     * @param sources 片段级 source 全集（kb:{ns}:{docNo}:v{ver}#{seq}；DB 切块表重放，精确无前缀歧义）
     * @return 实际删除条数（实现不支持统计时返回 -1）
     */
    int deleteBySources(Collection<String> sources);
}
