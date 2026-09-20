package com.agentdemo007.capability.kb.parse;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX 解析器（[[kb-ingest-design]]·任务2，POI XWPF）。
 *
 * <p>按 body 顺序遍历段落与表格：段落样式 {@code Heading1..6}（中文 Word 常见 {@code 1..6}、
 * {@code 标题 N} 同样识别）更新标题栈建节段；表格折叠为"单元格 | 单元格"行（保序进当前节段，
 * 表格前后的段落上下文不丢）。
 */
@org.springframework.stereotype.Component
public class DocxTextParser implements DocumentParser {

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)(?:heading|标题)\\s*([1-6])");

    @Override
    public Set<String> extensions() {
        return Set.of("docx");
    }

    @Override
    public ParsedDocument parse(InputStream in, String fileName) throws Exception {
        String title = MarkdownTextParser.fileNameBase(fileName);
        List<ParsedSection> sections = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean titleResolved = false;

        try (XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    int level = headingLevel(p);
                    String text = p.getText() == null ? "" : p.getText().strip();
                    if (level > 0 && !text.isBlank()) {
                        if (!buf.isEmpty()) {
                            sections.add(new ParsedSection(List.copyOf(stack), buf.toString()));
                            buf.setLength(0);
                        }
                        stack.subList(Math.min(level - 1, stack.size()), stack.size()).clear();
                        stack.add(text);
                        if (!titleResolved && level <= 2) {
                            title = text;
                            titleResolved = true;
                        }
                    } else if (!text.isBlank()) {
                        buf.append(text).append('\n');
                    }
                } else if (el instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText() == null ? "" : cell.getText().strip());
                        }
                        if (!cells.isEmpty() && cells.stream().anyMatch(c -> !c.isEmpty())) {
                            buf.append(String.join(" | ", cells)).append('\n');
                        }
                    }
                }
            }
        }
        if (!buf.isEmpty()) {
            sections.add(new ParsedSection(List.copyOf(stack), buf.toString()));
        }
        if (sections.isEmpty()) {
            sections.add(new ParsedSection(List.of(), ""));
        }
        return new ParsedDocument(title, "docx", sections);
    }

    /** 段落标题级别（1-6；非标题 → 0）。覆盖 Heading1..6 / "1".."6"（中文 Word 内置样式）/ 标题N。 */
    private static int headingLevel(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null || style.isBlank()) {
            return 0;
        }
        Matcher m = HEADING_STYLE.matcher(style);
        if (m.matches()) {
            return m.group(1).charAt(0) - '0';
        }
        return style.matches("[1-6]") ? style.charAt(0) - '0' : 0;
    }
}
