package com.agentdemo007.capability.kb.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CSV 解析器（[[kb-ingest-design]]·任务2，RFC 4180 口径迷你实现——引号/转义/换行内嵌）。
 *
 * <p>首行视为表头：数据行折叠为 {@code 列名: 值 | 列名: 值}（列名-值配对进文本，
 * 检索时可按列名命中）；行数上限 5000（截断记入节段尾注）。整表一个节段，
 * 切块按行打包（每行是天然原子）。
 */
@org.springframework.stereotype.Component
public class CsvTextParser implements DocumentParser {

    private static final int MAX_ROWS = 5000;

    @Override
    public Set<String> extensions() {
        return Set.of("csv", "tsv");
    }

    @Override
    public ParsedDocument parse(InputStream in, String fileName) throws Exception {
        String raw = TextCleaner.readAll(in);
        char sep = fileName != null && fileName.toLowerCase().endsWith(".tsv") ? '\t' : ',';
        List<List<String>> rows = parseRows(raw, sep);
        String title = MarkdownTextParser.fileNameBase(fileName);

        StringBuilder buf = new StringBuilder();
        if (!rows.isEmpty()) {
            List<String> header = rows.get(0);
            int emitted = 0;
            for (int r = 1; r < rows.size() && emitted < MAX_ROWS; r++, emitted++) {
                List<String> cells = rows.get(r);
                List<String> pairs = new ArrayList<>();
                for (int c = 0; c < header.size(); c++) {
                    String key = c < header.size() ? header.get(c) : "列" + (c + 1);
                    String val = c < cells.size() ? cells.get(c) : "";
                    if (!val.isBlank()) {
                        pairs.add((key.isBlank() ? "列" + (c + 1) : key) + ": " + val);
                    }
                }
                if (!pairs.isEmpty()) {
                    buf.append(String.join(" | ", pairs)).append('\n');
                }
            }
            if (emitted < rows.size() - 1) {
                buf.append("（超长表格，仅收录前 ").append(MAX_ROWS).append(" 行）").append('\n');
            }
        }
        return new ParsedDocument(title, "csv", List.of(new ParsedSection(List.of(), buf.toString())));
    }

    /** RFC 4180 迷你解析：双引号包裹（内部 "" 转义、可含分隔符/换行）；tsv 直接按制表符切。 */
    private static List<List<String>> parseRows(String raw, char sep) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean cellStarted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                inQuotes = true;
                cellStarted = true;
            } else if (c == sep) {
                cur.add(cellStarted ? cell.toString().strip() : cell.toString());
                cell.setLength(0);
                cellStarted = false;
            } else if (c == '\n') {
                cur.add(cell.toString());
                cell.setLength(0);
                cellStarted = false;
                rows.add(cur);
                cur = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !cur.isEmpty() || cellStarted) {
            cur.add(cell.toString());
            rows.add(cur);
        }
        return rows;
    }
}
