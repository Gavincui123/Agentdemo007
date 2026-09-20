package com.agentdemo007.capability.kb.parse;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * XLSX 解析器（[[kb-ingest-design]]·任务2，POI XSSF）。
 *
 * <p>每个工作表一个节段（标题=表名）；行折叠为"单元格 | 单元格"，表头行（首行）保留——
 * 结构语义（列名-值对应）由切块器按行打包保持。单元格统一 {@link DataFormatter} 取显示值
 * （日期/数字/公式缓存值不串型）；每表行数上限 2000 防超表拖垮内存（截断记入节段尾注）。
 */
@org.springframework.stereotype.Component
public class XlsxTableParser implements DocumentParser {

    private static final int MAX_ROWS_PER_SHEET = 2000;

    @Override
    public Set<String> extensions() {
        return Set.of("xlsx", "xlsm");
    }

    @Override
    public ParsedDocument parse(InputStream in, String fileName) throws Exception {
        String title = MarkdownTextParser.fileNameBase(fileName);
        List<ParsedSection> sections = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();

        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                XSSFSheet sheet = wb.getSheetAt(i);
                if (sheet == null) {
                    continue;
                }
                StringBuilder buf = new StringBuilder();
                int last = Math.min(sheet.getLastRowNum(), MAX_ROWS_PER_SHEET - 1);
                for (int r = sheet.getFirstRowNum(); r <= last; r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    List<String> cells = new ArrayList<>();
                    for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                        String v = fmt.formatCellValue(row.getCell(c));
                        cells.add(v == null ? "" : v.strip());
                    }
                    if (!cells.isEmpty() && cells.stream().anyMatch(v -> !v.isEmpty())) {
                        buf.append(String.join(" | ", cells)).append('\n');
                    }
                }
                if (last < sheet.getLastRowNum()) {
                    buf.append("（超长表格，仅收录前 ").append(MAX_ROWS_PER_SHEET).append(" 行）").append('\n');
                }
                sections.add(new ParsedSection(List.of(sheet.getSheetName()), buf.toString()));
            }
        }
        if (sections.isEmpty()) {
            sections.add(new ParsedSection(List.of(), ""));
        }
        return new ParsedDocument(title, "xlsx", sections);
    }
}
