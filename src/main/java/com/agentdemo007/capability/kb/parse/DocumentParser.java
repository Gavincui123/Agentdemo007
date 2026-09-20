package com.agentdemo007.capability.kb.parse;

import java.io.InputStream;
import java.util.Set;

/**
 * 文档解析器 seam（[[kb-ingest-design]]·任务2 多格式文件处理）。
 *
 * <p>一个实现管一族格式（按扩展名路由，{@link DocumentParserRegistry} 消费）；
 * 解析失败抛异常由录入服务统一兜底（该文件拒绝入库，不产生半截知识）。
 * 职责边界：只还原结构（标题路径 + 节段流），<b>不清洗、不切块</b>。
 */
public interface DocumentParser {

    /** 本解析器接管的扩展名（小写、不含点，如 "pdf"；路由键）。 */
    Set<String> extensions();

    /**
     * 解析输入流为结构化文档。
     *
     * @param in       文件内容（调用方负责关闭；实现内不得关闭）
     * @param fileName 原始文件名（标题兜底/格式内嵌标题缺失时使用）
     */
    ParsedDocument parse(InputStream in, String fileName) throws Exception;
}
