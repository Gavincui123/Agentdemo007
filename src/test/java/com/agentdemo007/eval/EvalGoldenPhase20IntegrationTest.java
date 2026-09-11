package com.agentdemo007.eval;

import com.agentdemo007.capability.rag.RagStep;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.session.model.StandardQuery;
import com.agentdemo007.session.rewrite.QueryEnricher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 20 eval golden 期望值**端到端**守卫（code-review #3）。
 *
 * <p>T92 把 {@code queryEnrichmentContains}/{@code ragFragmentsContain}/{@code hasFragments} 从
 * "文档字段被静默丢弃"改成真对照后，{@link GoldenSuiteTest} 的 trivial 执行器无法满足这些字段
 * （不跑真实流水线）→ rag/understanding 两个 stage 的 Phase 20 用例在冒烟里**全红但非回归**。
 * 更要紧的是：Phase 20 golden 期望值（ORD123456 抽取 / Hybrid 命中 / 历史标注）**从未对真实
 * 流水线验证过**——{@link EvalExecutorPhase20Test} 用 fake 执行器只测对照逻辑，不验产出。
 *
 * <p>本集成测试用**真实 bean**（{@link QueryEnricher} + {@link RagStep}，后者装配 {@code @Primary}
 * {@code HybridRetriever} + {@code RagSeedRunner} 已播种的 {@code InMemoryVectorStore}）跑 Phase 20
 * 的四例输入，断言 eval 期望值真成立。属 characterization 守卫（{@link GoldenSuiteTest} 先例：
 * 立即通过即合法回归守卫）；若任一失败则暴露抽取/检索/标注的真实回归，须修根因。
 *
 * <p>dev 确定性（无 LLM）：QueryEnricher 正则、RagStep 召回/重排/标注均不依赖模型，可稳定复现。
 */
@SpringBootTest
class EvalGoldenPhase20IntegrationTest {

    @Autowired
    private QueryEnricher queryEnricher;

    @Autowired
    private RagStep ragStep;

    /** und-006：约束改写补全精确词（订单号）入 QueryEnrichment。 */
    @Test
    void und006_orderId_extractedToEnrichment() {
        assertThat(queryEnricher.enrich("查订单 ORD123456").keywords()).contains("ORD123456");
    }

    /** und-007：CJK 词典精确词（发票类型）入 QueryEnrichment。 */
    @Test
    void und007_invoiceType_extractedToEnrichment() {
        assertThat(queryEnricher.enrich("帮我开增值税专用发票").keywords()).contains("增值税专用发票");
    }

    /** rag-006：Hybrid 关键词通道精确命中 kb-order 种子，片段入 ragFragments。 */
    @Test
    void rag006_orderId_fragmentRetrieved() {
        PipelineContext ctx = new PipelineContext("eval-rag006", "查订单 ORD123456");
        ctx.setStandardQuery(StandardQuery.of("查订单 ORD123456"));

        ragStep.process(ctx);

        assertThat(ctx.ragFragments())
                .as("Hybrid 关键词通道应精确命中含 ORD123456 的 kb-order 片段")
                .anyMatch(f -> f.contains("ORD123456"));
    }

    /** rag-007：历史片段经 displayText 时效隔离标注，ragFragments 含"历史参考资料"。 */
    @Test
    void rag007_historicalFragment_framedWithLabel() {
        PipelineContext ctx = new PipelineContext("eval-rag007", "旧退款政策");
        ctx.setStandardQuery(StandardQuery.of("旧退款政策"));

        ragStep.process(ctx);

        assertThat(ctx.ragFragments())
                .as("历史片段应经 displayText 标注『历史参考资料』，过时不冒充当前")
                .anyMatch(f -> f.contains("历史参考资料"));
    }
}
