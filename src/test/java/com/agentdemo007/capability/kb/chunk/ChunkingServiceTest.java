package com.agentdemo007.capability.kb;

import com.agentdemo007.capability.kb.chunk.ChunkOptions;
import com.agentdemo007.capability.kb.chunk.ChunkingService;
import com.agentdemo007.capability.kb.chunk.ProposedChunk;
import com.agentdemo007.capability.kb.parse.ParsedDocument;
import com.agentdemo007.capability.kb.parse.ParsedSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切块服务单测（[[kb-ingest-design]]·任务2）：通用递归切块（打包/重叠/短尾合并）与
 * 专业领域条款感知切块（自动识别、条款原子、承接前缀）。
 */
class ChunkingServiceTest {

    private final ChunkingService service = new ChunkingService();
    private final ChunkOptions opts = ChunkOptions.of(500, 80);

    @Test
    void paragraphsPackedWithinLimit() {
        String body = ("第一段内容。".repeat(50) + "\n").repeat(5); // 每段 300 字 ×5 → 必然多块
        List<ProposedChunk> chunks = service.chunk(new ParsedDocument("标题", "txt",
                List.of(new ParsedSection(List.of(), body))), opts);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        for (ProposedChunk c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(500);
        }
        assertThat(chunks.get(0).seq()).isEqualTo(1);
    }

    @Test
    void oversizeParagraphSplitWithOverlap() {
        char[] big = new char[1200];
        java.util.Arrays.fill(big, '字');
        for (int i = 20; i < 1200; i += 40) {
            big[i] = '。'; // 句号分隔给递归切点
        }
        List<ProposedChunk> chunks = service.chunk(new ParsedDocument("长文", "txt",
                List.of(new ParsedSection(List.of(), new String(big)))), opts);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        // 相邻块有重叠窗口（非硬边界）
        String tail1 = chunks.get(0).text().substring(Math.max(0, chunks.get(0).text().length() - 40));
        assertThat(chunks.get(1).text()).contains(tail1.substring(0, Math.min(10, tail1.length())));
    }

    @Test
    void clauseModeDetectedForPolicyTextAndClausesStayAtomic() {
        String body = """
                第一章 总则
                第一条 退货政策：签收后7日内可申请退货，商品须完好未使用。
                第二条 退款时效：原支付渠道3-7个工作日到账。
                第三条 运费规则：无理由退货运费买家承担，质量问题运费卖家承担。
                第四条 特殊商品：生鲜食品、定制商品不支持无理由退货。
                """;
        ParsedDocument doc = new ParsedDocument("售后政策", "md",
                List.of(new ParsedSection(List.of("售后"), body)));
        assertThat(ChunkingService.detectClauseMode(doc, opts)).isTrue();

        List<ProposedChunk> chunks = service.chunk(doc, opts);
        // 条款原子性：含"第一条"标记的块必须完整包含该条款正文（条款不被拦腰切开；
        // 小条款允许同簇打包，簇边界不落在条款内部）
        for (ProposedChunk c : chunks) {
            if (c.text().contains("第一条")) {
                assertThat(c.text()).contains("商品须完好未使用。");
            }
            if (c.text().contains("第四条")) {
                assertThat(c.text()).contains("不支持无理由退货。");
            }
        }
        String joined = chunks.stream().map(ProposedChunk::text).reduce("", (a, b) -> a + "\n" + b);
        assertThat(joined).contains("第四条 特殊商品");
    }

    @Test
    void generalModeForNonClauseText() {
        ParsedDocument doc = new ParsedDocument("随笔", "txt",
                List.of(new ParsedSection(List.of(), "今天天气不错。\n明天也一样。")));
        assertThat(ChunkingService.detectClauseMode(doc, opts)).isFalse();
        List<ProposedChunk> chunks = service.chunk(doc, opts);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("今天天气不错。").contains("明天也一样。");
    }

    @Test
    void oversizeClauseSplitCarriesContinuationAnchor() {
        // 单条款超长（> 500 字）→ 内部递归切分且第二段带"（承接第X条）"
        String longClause = "第五条 综合保障规则：" + "内容详述。".repeat(120);
        ParsedDocument doc = new ParsedDocument("规章", "md", List.of(
                new ParsedSection(List.of(), "第一条 简则。\n第二条 简则二。\n第三条 简则三。\n" + longClause)));
        List<ProposedChunk> chunks = service.chunk(doc, opts);
        assertThat(chunks.stream().anyMatch(c -> c.text().startsWith("（承接第五条"))).isTrue();
    }

    @Test
    void emptyDocumentYieldsSingleEmptyChunk() {
        List<ProposedChunk> chunks = service.chunk(new ParsedDocument("空", "txt",
                List.of(new ParsedSection(List.of(), "  "))), opts);
        assertThat(chunks).hasSize(1);
    }
}
