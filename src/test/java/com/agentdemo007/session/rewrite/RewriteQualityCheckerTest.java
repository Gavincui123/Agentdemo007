package com.agentdemo007.session.rewrite;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.model.QueryEnrichment;
import com.agentdemo007.session.model.StandardQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 改写质量校验步骤测试（第二层·RewriteQualityChecker @Order(400)）。
 *
 * <p>对 {@code QueryRewriter} 产出的改写做结构级完整性/忠实度校验（§5.2.3）：
 * 被注入隔离定界符污染、空、长度失控等"失真"情形 → 回退 {@code rawInput}（②每步降级，不阻塞）。
 *
 * <p>语义级忠实度（深层语义漂移检测）需 LLM 判定，延后至流式/小模型接入后扩展；
 * 当前结构级校验已能拦住明显的失真产出，保证下游拿到的是自足 Query。
 */
class RewriteQualityCheckerTest {

    private final RewriteQualityChecker checker = new RewriteQualityChecker();

    @Test
    void goodRewrite_passes_keepsStandardQuery() {
        PipelineContext ctx = new PipelineContext("s1", "那它呢");
        ctx.setStandardQuery(StandardQuery.of("Q3 销售额怎么样"));

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("Q3 销售额怎么样"));
    }

    @Test
    void rewriteEqualsRawInput_passes() {
        PipelineContext ctx = new PipelineContext("s1", "查 Q3 销售");
        ctx.setStandardQuery(StandardQuery.of("查 Q3 销售"));

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("查 Q3 销售"));
    }

    @Test
    void corruptedWithSanitizerMarkers_fallsBackToRawInput() {
        PipelineContext ctx = new PipelineContext("s1", "它怎么样");
        ctx.setStandardQuery(StandardQuery.of(PromptSanitizer.OPEN + " 被污染的改写 " + PromptSanitizer.CLOSE));

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("它怎么样")); // 回退原问题
    }

    @Test
    void runawayTooLong_fallsBackToRawInput() {
        PipelineContext ctx = new PipelineContext("s1", "它怎么样");
        ctx.setStandardQuery(StandardQuery.of("a".repeat(3000)));

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("它怎么样"));
    }

    @Test
    void name_isRewriteQualityChecker() {
        assertThat(checker.name()).isEqualTo("RewriteQualityChecker");
    }

    /**
     * code-review #2：质量校验回退 standardQuery 时须同步重置 queryEnrichment——
     * 否则补全槽残留坏改写抽出的词（与回退后的 standardQuery 不一致，误导 eval/审计）。
     * 检索链路（HybridRetriever）自行 enrich(query) 不读此槽，故检索不受影响；
     * 但 ③环节测评 queryEnrichmentContains 对照的是此槽，残留则断言错位。
     */
    @Test
    void fallback_resetsQueryEnrichment_fromRawInput_notStaleFromBadRewrite() {
        // 坏改写（含定界符污染）→ 质量校验回退 rawInput
        PipelineContext ctx = new PipelineContext("s1", "查订单 ORD123456");
        ctx.setStandardQuery(StandardQuery.of(PromptSanitizer.OPEN + " 污染 " + PromptSanitizer.CLOSE));
        // 模拟 QueryRewriter 已据"坏改写"设了补全槽（坏改写无精确词 → EMPTY）
        ctx.setQueryEnrichment(QueryEnrichment.EMPTY);

        StepOutcome outcome = checker.process(ctx);

        assertThat(outcome).isInstanceOf(StepOutcome.Proceed.class);
        assertThat(ctx.standardQuery()).isEqualTo(StandardQuery.of("查订单 ORD123456"));
        // 回退后补全槽须从 rawInput 重抽——含 ORD123456（非残留的 EMPTY）
        assertThat(ctx.queryEnrichment().keywords()).contains("ORD123456");
    }
}
