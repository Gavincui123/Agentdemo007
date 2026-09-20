package com.agentdemo007.capability.kb.parse;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PDF 解析器（[[kb-ingest-design]]·任务2，PDFBox 3）。
 *
 * <p>逐页抽取（{@code 第N页} 节段，PDF 无可靠标题结构——标题层级交给后置切块的
 * 条款编号识别兜底）；扫描件无文本层时页文本为空，节段自然为空、由录入服务以
 * "可提取字符数=0" 拒绝（OCR 属 Python 流水线能力，Java 侧不做）。
 */
@org.springframework.stereotype.Component
public class PdfTextParser implements DocumentParser {

    @Override
    public Set<String> extensions() {
        return Set.of("pdf");
    }

    @Override
    public ParsedDocument parse(InputStream in, String fileName) throws Exception {
        String title = MarkdownTextParser.fileNameBase(fileName);
        List<ParsedSection> sections = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc);
                if (text != null && !text.isBlank()) {
                    sections.add(new ParsedSection(List.of("第" + page + "页"), text));
                }
            }
        }
        if (sections.isEmpty()) {
            // 无文本层（扫描件）：明确留空，录入服务以"可提取字符数=0"拒绝并提示走 Python OCR 流水线
            sections.add(new ParsedSection(List.of(), ""));
        }
        return new ParsedDocument(title, "pdf", sections);
    }
}
