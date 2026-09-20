package com.agentdemo007.capability.kb.parse;

import java.util.List;

/**
 * 解析产出节段（[[kb-ingest-design]]·任务2）：带标题路径的连续文本块。
 *
 * <p>解析器的职责边界：<b>不切块、不清洗</b>，只把"任意格式文件"还原为
 * 结构化节段流（标题路径 + 节段正文）——结构信息是后续专业领域切块的原料
 * （条款切块依赖 heading 上下文，通用切块按节段打包）。
 *
 * @param headingPath 标题路径（自外向内，如 ["售后政策","退货"]；可空=无结构平文）
 * @param text        节段正文（未清洗原始文本）
 */
public record ParsedSection(List<String> headingPath, String text) {

    public ParsedSection {
        headingPath = (headingPath == null) ? List.of() : List.copyOf(headingPath);
    }
}
