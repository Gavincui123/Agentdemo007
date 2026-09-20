package com.agentdemo007.capability.kb.parse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown / 纯文本解析器（[[kb-ingest-design]]·任务2）。
 *
 * <p>Markdown 按 {@code #..######} 标题层级维护标题栈建节段（同层出栈、深层入栈）；
 * 纯文本（.txt）无结构语义，退化为单节段（标题路径空，切块按段落打包）。
 * 内容提取的标题即文档标题（首个一级/二级标题，缺省文件名兜底）。
 */
@org.springframework.stereotype.Component
public class MarkdownTextParser implements DocumentParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    @Override
    public Set<String> extensions() {
        return Set.of("md", "markdown", "txt");
    }

    @Override
    public ParsedDocument parse(InputStream in, String fileName) throws Exception {
        String raw = TextCleaner.readAll(in);
        String title = fileNameBase(fileName);

        if (!isMarkdown(fileName, raw)) {
            return new ParsedDocument(title, "txt", List.of(new ParsedSection(List.of(), raw)));
        }

        List<ParsedSection> sections = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        boolean titleResolved = false;

        for (String line : raw.split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (!m.matches()) {
                buf.append(line).append('\n');
                continue;
            }
            if (!buf.isEmpty()) {
                sections.add(new ParsedSection(List.copyOf(stack), buf.toString()));
                buf.setLength(0);
            }
            int level = m.group(1).length();
            String heading = m.group(2).strip();
            stack.subList(Math.min(level - 1, stack.size()), stack.size()).clear();
            stack.add(heading);
            if (!titleResolved && level <= 2 && !heading.isBlank()) {
                title = heading; // 首个一/二级标题即文档标题
                titleResolved = true;
            }
        }
        if (!buf.isEmpty()) {
            sections.add(new ParsedSection(List.copyOf(stack), buf.toString()));
        }
        if (sections.isEmpty()) {
            sections.add(new ParsedSection(List.of(), ""));
        }
        return new ParsedDocument(title, "md", sections);
    }

    private static boolean isMarkdown(String fileName, String raw) {
        if (fileName != null && fileName.toLowerCase().matches(".*\\.(md|markdown)$")) {
            return true;
        }
        return raw.lines().limit(30).anyMatch(l -> HEADING.matcher(l).matches());
    }

    /** 文件名去路径去扩展名（各解析器共用的标题兜底）。 */
    public static String fileNameBase(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "未命名文档";
        }
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.isBlank() ? fileName : base;
    }
}
