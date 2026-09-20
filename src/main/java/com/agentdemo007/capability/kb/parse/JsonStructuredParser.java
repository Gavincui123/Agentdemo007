package com.agentdemo007.capability.kb.parse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON 结构化知识解析器（[[kb-ingest-design]]·任务2，Jackson 3）。
 *
 * <p>面向"FAQ 对/结构化条目"知识形态：
 * <ul>
 *   <li>顶层数组 → 每个元素一个节段（对象按 {@code key: value} 行展开，标题路径 {@code 条目#i}）；</li>
 *   <li>顶层对象 → 单节段 {@code key: value} 行展开（嵌套对象/数组折叠为 JSON 串）；</li>
 *   <li>其它（标量/解析失败）→ 原文单节段兜底。</li>
 * </ul>
 */
@org.springframework.stereotype.Component
public class JsonStructuredParser implements DocumentParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public Set<String> extensions() {
        return Set.of("json");
    }

    @Override
    public ParsedDocument parse(InputStream in, String fileName) throws Exception {
        String raw = TextCleaner.readAll(in);
        String title = MarkdownTextParser.fileNameBase(fileName);
        List<ParsedSection> sections = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root.isArray()) {
                int i = 1;
                for (Iterator<JsonNode> it = root.iterator(); it.hasNext(); i++) {
                    sections.add(new ParsedSection(List.of("条目#" + i), flatten(it.next())));
                }
            } else if (root.isObject()) {
                sections.add(new ParsedSection(List.of(), flatten(root)));
            } else {
                sections.add(new ParsedSection(List.of(), raw));
            }
        } catch (Exception e) {
            sections.add(new ParsedSection(List.of(), raw)); // 非法 JSON：平文兜底，清洗切块照走
        }
        if (sections.isEmpty()) {
            sections.add(new ParsedSection(List.of(), ""));
        }
        return new ParsedDocument(title, "json", sections);
    }

    /** 节点 → "key: value" 行文本（数组/嵌套对象折叠为紧凑 JSON 串，保信息量不展开无谓层级）。 */
    private static String flatten(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        StringBuilder sb = new StringBuilder();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                sb.append(e.getKey()).append(": ").append(scalarOrJson(e.getValue())).append('\n');
            }
        } else {
            for (JsonNode item : node) {
                sb.append("- ").append(scalarOrJson(item)).append('\n');
            }
        }
        return sb.toString().strip();
    }

    private static String scalarOrJson(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        if (n.isValueNode()) {
            return n.asText();
        }
        return MAPPER.writeValueAsString(n);
    }
}
