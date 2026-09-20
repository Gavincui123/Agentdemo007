package com.agentdemo007.capability.kb.parse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文本清洗器（[[kb-ingest-design]]·任务2 录入前清洗）。
 *
 * <p>录入链序：解析（结构还原）→ <b>清洗（本类）</b> → 切块。只做无损/低损规整，
 * 不做语义删改——目标是通过向量库/BM25 分词器的"脏输入"关：
 * <ol>
 *   <li>Unicode NFKC 规整（全角字母数字/全角空格→半角，兼容嵌入与词面匹配）；</li>
 *   <li>换行统一 LF、\u00A0→空格、零宽字符与控制字符剔除（保留 \n/\t）；</li>
 *   <li>页码行剔除（PDF 抽取最常见的页眉页脚形态："- 3 -" / "第 3 页" / 纯数字行）；</li>
 *   <li>行内空白折叠、行尾空白去除、3 连以上空行压缩为 1 空行。</li>
 * </ol>
 */
public final class TextCleaner {

    private TextCleaner() {
    }

    /** 清洗一段原始文本（null 安全 → 空串）。 */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC);
        s = s.replace("\r\n", "\n").replace("\r", "\n")
                .replace("\u00A0", " ")
                .replace("\u200B", "").replace("\u200C", "").replace("\u200D", "").replace("\uFEFF", "");
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t' || c >= ' ') {
                out.append(c);
            }
        }
        s = out.toString();

        StringBuilder lines = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            String cleaned = line.replaceAll("[ \\t]+", " ").strip();
            if (isPageNumberLine(cleaned)) {
                continue; // 页码行剔除（页眉页脚噪声）
            }
            lines.append(cleaned).append('\n');
        }
        return lines.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    /** 页码行判定：纯数字 / - 3 - / — 12 — / 第 3 页 / Page 3 形态（长度≤16 的孤立行）。 */
    private static boolean isPageNumberLine(String line) {
        if (line.isEmpty() || line.length() > 16) {
            return false;
        }
        return line.matches("[-–—=·\\s]*\\d{1,4}[-–—=·\\s]*")
                || line.matches("第\\s*\\d{1,4}\\s*页")
                || line.matches("(?i)page\\s*\\d{1,4}")
                || line.matches("(?i)第\\s*\\d{1,4}\\s*/\\s*\\d{1,4}\\s*页");
    }

    /** 读入流为 UTF-8 文本（txt/md/csv/json/html 通用；调用方负责关闭流）。 */
    public static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
}
