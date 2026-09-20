package com.agentdemo007.capability.kb.parse;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * HTML 解析器（[[kb-ingest-design]]·任务2，jsoup）。
 *
 * <p>按文档序遍历 body：h1-h6 更新标题栈建节段，p/li/pre/blockquote 等叶子块收正文；
 * script/style/nav/header/footer/aside 剔除（站点噪声不进知识库）。
 * 表格整体折叠为"单元格 | 单元格"行文本——结构交给切块器按行打包。
 */
@org.springframework.stereotype.Component
public class HtmlTextParser implements DocumentParser {

    private static final Set<String> SKIP = Set.of("script", "style", "nav", "header", "footer",
            "aside", "noscript", "iframe", "svg", "form", "button");

    @Override
    public Set<String> extensions() {
        return Set.of("html", "htm");
    }

    @Override
    public ParsedDocument parse(InputStream in, String fileName) throws Exception {
        org.jsoup.nodes.Document doc = Jsoup.parse(in, "UTF-8", "");
        String title = (doc.title() != null && !doc.title().isBlank())
                ? doc.title().strip() : MarkdownTextParser.fileNameBase(fileName);

        List<ParsedSection> sections = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        walk(doc.body(), stack, buf, sections);
        if (!buf.isEmpty()) {
            sections.add(new ParsedSection(List.copyOf(stack), buf.toString()));
        }
        if (sections.isEmpty()) {
            sections.add(new ParsedSection(List.of(), ""));
        }
        return new ParsedDocument(title, "html", sections);
    }

    private void walk(Element el, List<String> stack, StringBuilder buf, List<ParsedSection> sections) {
        String tag = el.tagName().toLowerCase();
        if (SKIP.contains(tag)) {
            return;
        }
        if (tag.matches("h[1-6]")) {
            if (!buf.isEmpty()) {
                sections.add(new ParsedSection(List.copyOf(stack), buf.toString()));
                buf.setLength(0);
            }
            int level = tag.charAt(1) - '0';
            String heading = el.text().strip();
            if (!heading.isBlank()) {
                stack.subList(Math.min(level - 1, stack.size()), stack.size()).clear();
                stack.add(heading);
            }
            return;
        }
        if (tag.equals("table")) {
            String t = tableText(el);
            if (!t.isBlank()) {
                buf.append(t).append('\n');
            }
            return;
        }
        if (tag.equals("p") || tag.equals("pre") || tag.equals("blockquote")) {
            String text = el.text().strip();
            if (!text.isBlank()) {
                buf.append(text).append('\n');
            }
            return;
        }
        if (tag.equals("li")) {
            String direct = directText(el); // li 嵌套防重复：只收自身直接文本，子块递归
            if (!direct.isBlank()) {
                buf.append(direct).append('\n');
            }
            for (Element child : el.children()) {
                walk(child, stack, buf, sections);
            }
            return;
        }
        for (Element child : el.children()) {
            walk(child, stack, buf, sections);
        }
    }

    /** 元素自身直接文本（不含子元素文本）。 */
    private String directText(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Node n : el.childNodes()) {
            if (n instanceof TextNode t) {
                sb.append(t.text()).append(' ');
            }
        }
        return sb.toString().strip();
    }

    private String tableText(Element table) {
        StringBuilder sb = new StringBuilder();
        for (Element row : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.select("th,td")) {
                cells.add(cell.text().strip());
            }
            if (!cells.isEmpty() && cells.stream().anyMatch(c -> !c.isEmpty())) {
                sb.append(String.join(" | ", cells)).append('\n');
            }
        }
        return sb.toString().strip();
    }
}
