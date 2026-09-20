package com.agentdemo007.capability.kb.parse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多格式解析器单测（[[kb-ingest-design]]·任务2）：Markdown 标题栈 / HTML 结构 /
 * CSV 列值配对 / JSON 条目展开 / 注册表路由与平文兜底。
 * （DOCX/XLSX/PDF 为二进制格式，解析正确性由录入流程测试与 Python 流水线口径对齐兜底，
 * 纯单测不造假二进制 fixture。）
 */
class DocumentParsersTest {

    private static ByteArrayInputStream in(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void markdownHeadingsBuildSectionStack() throws Exception {
        MarkdownTextParser parser = new MarkdownTextParser();
        ParsedDocument doc = parser.parse(in("""
                # 退货政策
                总体说明文字。
                ## 无理由退货
                签收后7日内可申请。
                ## 质量问题退货
                不限时限，凭凭证办理。
                """), "faq.md");

        assertThat(doc.title()).isEqualTo("退货政策");
        assertThat(doc.sections()).hasSize(3);
        assertThat(doc.sections().get(0).headingPath()).containsExactly("退货政策");
        assertThat(doc.sections().get(1).headingPath()).containsExactly("退货政策", "无理由退货");
        assertThat(doc.sections().get(2).text()).contains("凭凭证办理");
    }

    @Test
    void plainTextDegradesToSingleSection() throws Exception {
        MarkdownTextParser parser = new MarkdownTextParser();
        ParsedDocument doc = parser.parse(in("第一段。\n第二段。"), "notes.txt");
        assertThat(doc.docType()).isEqualTo("txt");
        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).headingPath()).isEmpty();
    }

    @Test
    void htmlExtractsHeadingsAndSkipsNoise() throws Exception {
        HtmlTextParser parser = new HtmlTextParser();
        ParsedDocument doc = parser.parse(in("""
                <html><head><title>会员权益</title></head><body>
                <script>alert('noise')</script>
                <h1>会员体系</h1><p>消费满千元升银卡。</p>
                <h2>金牌权益</h2><ul><li>会员价</li><li>满减叠加</li></ul>
                </body></html>
                """), "member.html");

        assertThat(doc.title()).isEqualTo("会员权益");
        assertThat(doc.sections().get(0).headingPath()).containsExactly("会员体系");
        assertThat(doc.sections().get(1).headingPath()).containsExactly("会员体系", "金牌权益");
        String body = String.join("\n", doc.sections().stream().map(ParsedSection::text).toList());
        assertThat(body).doesNotContain("alert").contains("消费满千元升银卡").contains("满减叠加");
    }

    @Test
    void csvPairsHeaderWithValues() throws Exception {
        CsvTextParser parser = new CsvTextParser();
        ParsedDocument doc = parser.parse(in("商品,价格,库存\n无线耳机,199,50\n手机壳,29,200\n"),
                "products.csv");

        String body = doc.sections().get(0).text();
        assertThat(body).contains("商品: 无线耳机 | 价格: 199 | 库存: 50");
        assertThat(body).contains("商品: 手机壳");
    }

    @Test
    void jsonArrayOfObjectsExpandsToPerItemSections() throws Exception {
        JsonStructuredParser parser = new JsonStructuredParser();
        ParsedDocument doc = parser.parse(in("""
                [
                  {"question": "退款多久到账", "answer": "3-7个工作日"},
                  {"question": "如何开发票", "answer": "订单页申请"}
                ]
                """), "faq.json");

        assertThat(doc.sections()).hasSize(2);
        assertThat(doc.sections().get(0).headingPath()).containsExactly("条目#1");
        assertThat(doc.sections().get(0).text()).contains("question: 退款多久到账").contains("answer: 3-7个工作日");
    }

    @Test
    void registryRoutesByExtensionAndFallsBackToPlainText() throws Exception {
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(
                new MarkdownTextParser(), new HtmlTextParser(), new CsvTextParser(),
                new JsonStructuredParser(), new PdfTextParser(), new DocxTextParser(),
                new XlsxTableParser()));

        assertThat(registry.supports("faq.md")).isTrue();
        assertThat(registry.supports("photo.pdf")).isTrue();
        assertThat(registry.supports("archive.zip")).isFalse();

        ParsedDocument fallback = registry.parse("archive.zip", in("纯文本内容"));
        assertThat(fallback.docType()).isEqualTo("txt");
        assertThat(fallback.sections().get(0).text()).contains("纯文本内容");

        ParsedDocument routed = registry.parse("faq.md", in("# 标题\n内容"));
        assertThat(routed.docType()).isEqualTo("md");
    }
}
