package com.agentdemo007.capability.kb.parse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本清洗单测（[[kb-ingest-design]]·任务2）：NFKC、页码行、零宽/控制字符、空白规整。
 */
class TextCleanerTest {

    @Test
    void normalizesFullWidthAndZeroWidth() {
        String out = TextCleaner.clean("ＡＢＣ１２３\u200B隐藏\uFEFF字符");
        assertThat(out).isEqualTo("ABC123隐藏字符");
    }

    @Test
    void stripsPageNumberFooterLines() {
        String out = TextCleaner.clean("正文第一行\n- 3 -\n第 12 页\nPage 4\n正文第二行");
        assertThat(out).isEqualTo("正文第一行\n正文第二行");
    }

    @Test
    void keepsContentNumbers() {
        String out = TextCleaner.clean("退款时效 3-7 个工作日");
        assertThat(out).isEqualTo("退款时效 3-7 个工作日");
    }

    @Test
    void collapsesBlankRunsAndTrimsLines() {
        String out = TextCleaner.clean("A行\n\n\n\n  B行  \n\nC行\n");
        assertThat(out).isEqualTo("A行\n\nB行\n\nC行");
    }

    @Test
    void removesControlCharsAndCollapsesTab() {
        String out = TextCleaner.clean("A\u0001B\tC\nD");
        assertThat(out).isEqualTo("AB C\nD"); // 制表符按行内空白折叠为单空格（清洗口径）
    }

    @Test
    void readAllDecodesUtf8() throws Exception {
        String text = TextCleaner.readAll(new ByteArrayInputStream("中文内容".getBytes(StandardCharsets.UTF_8)));
        assertThat(text).isEqualTo("中文内容");
    }
}
