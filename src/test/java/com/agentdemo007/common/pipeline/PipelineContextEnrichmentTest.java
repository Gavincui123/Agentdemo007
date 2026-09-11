package com.agentdemo007.common.pipeline;

import com.agentdemo007.session.model.QueryEnrichment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PipelineContext} 查询补全槽字段测评（Phase 20·T88b）。
 *
 * <p>锁 {@code queryEnrichment} 强类型收口字段（§5.14：禁止各步私造，统一收口于 context）：
 * 缺省 {@link QueryEnrichment#EMPTY}（非 null，消费者可安全调 {@code .keywords()} 无 NPE），
 * 经 {@code setQueryEnrichment} 回填后原样读出。约束改写步骤（QueryRewriter）产，
 * Hybrid RAG（T89）消费。
 */
class PipelineContextEnrichmentTest {

    @Test
    void queryEnrichment_defaultsEmpty_andRoundTrips() {
        PipelineContext ctx = new PipelineContext("s1", "查订单 ORD123456");

        assertThat(ctx.queryEnrichment()).isEqualTo(QueryEnrichment.EMPTY); // 缺省非 null

        QueryEnrichment enr = new QueryEnrichment(List.of("ORD123456"), "Q3");
        ctx.setQueryEnrichment(enr);

        assertThat(ctx.queryEnrichment()).isEqualTo(enr);
        assertThat(ctx.queryEnrichment().keywords()).containsExactly("ORD123456");
        assertThat(ctx.queryEnrichment().timeline()).isEqualTo("Q3");
    }
}
