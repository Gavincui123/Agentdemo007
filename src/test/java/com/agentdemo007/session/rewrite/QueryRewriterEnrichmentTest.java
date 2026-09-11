package com.agentdemo007.session.rewrite;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.model.QueryEnrichment;
import com.agentdemo007.session.model.StandardQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link QueryRewriter} 约束改写补全槽测评（Phase 20·T88c）。
 *
 * <p>改写产出经 {@link QueryEnricher} 抽取精确词/时间线写入 {@code context.queryEnrichment}
 * ——只补全不替换：{@code standardQuery} 仍为自足改写（供意图/RAG 消费，既有行为不变），
 * 补全槽**不覆盖** standardQuery（§5.2.4：产出 original query + QueryEnrichment，原查询经 rawInput 保留）。
 * 改写失败/空 → 回退原问题，补全槽从原问题抽取（②每步降级不变）。
 */
class QueryRewriterEnrichmentTest {

    private final ChatLlmService llm = mock(ChatLlmService.class);
    private final QueryEnricher enricher = new QueryEnricher();
    private final QueryRewriter rewriter = new QueryRewriter(llm, enricher);

    @Test
    void rewrite_populatesEnrichmentFromRewriteText_standardQueryUnchanged() {
        when(llm.decide(anyString()))
                .thenReturn("查订单 ORD123456 在 Q3 的状态");
        PipelineContext ctx = new PipelineContext("s1", "查订单");
        ctx.setHistory(List.of(new ChatMessage.User("看 Q3 销售"), new ChatMessage.Ai("好的")));

        StepOutcome outcome = rewriter.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        // standardQuery 仍为自足改写（既有行为不变，不覆盖）
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("查订单 ORD123456 在 Q3 的状态"));
        // 补全槽从改写文本抽取精确词/时间线
        assertThat(ctx.queryEnrichment().keywords()).containsExactly("ORD123456");
        assertThat(ctx.queryEnrichment().timeline()).isEqualTo("Q3");
    }

    @Test
    void rewriteFallsBack_enrichmentExtractedFromRawInput() {
        when(llm.decide(anyString())).thenReturn("   "); // 空输出→回退
        PipelineContext ctx = new PipelineContext("s1", "查订单 12345678");

        rewriter.process(ctx);

        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("查订单 12345678"));
        assertThat(ctx.queryEnrichment().keywords()).containsExactly("12345678");
    }

    @Test
    void rewriteNoPreciseTerms_enrichmentEmpty() {
        when(llm.decide(anyString())).thenReturn("退款流程说明");
        PipelineContext ctx = new PipelineContext("s1", "退款");

        rewriter.process(ctx);

        assertThat(ctx.queryEnrichment()).isEqualTo(QueryEnrichment.EMPTY);
    }
}
