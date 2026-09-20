package com.agentdemo007.capability.kb.parse;

import java.util.List;

/**
 * 解析产出文档（[[kb-ingest-design]]·任务2）：标题 + 类型 + 有序节段流。
 *
 * @param title    文档标题（解析器从内容提取，如 Markdown 一级标题 / HTML title / DOCX Heading1；
 *                 缺省由调用方以文件名兜底）
 * @param docType  规范化文档类型（md/txt/html/pdf/docx/xlsx/csv/json）
 * @param sections 有序节段流（非空；平文文档为单节段）
 */
public record ParsedDocument(String title, String docType, List<ParsedSection> sections) {

    public ParsedDocument {
        sections = (sections == null) ? List.of() : List.copyOf(sections);
    }
}
