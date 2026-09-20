package com.agentdemo007.capability.kb.chunk;

/**
 * 切块产出（[[kb-ingest-design]]·任务2）：预览/入库共用的最小载体。
 *
 * @param seq      版本内序号（1 起，与向量 source #{seq} 一致）
 * @param heading  标题面包屑（"标题 › 章 › 节"，空标题文档为空串）
 * @param text     切块正文（<b>不含</b>面包屑；入库时由录入服务拼 breadcrumb 进向量文本）
 */
public record ProposedChunk(int seq, String heading, String text) {

    /** 向量库实际索引文本：面包屑首行 + 正文（面包屑给嵌入/BM25 提供文档上下文，提升召回）。 */
    public String indexedText() {
        return (heading == null || heading.isBlank()) ? text : "【" + heading + "】\n" + text;
    }
}
