package com.agentdemo007.capability.rag;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.observability.AgentMetrics;
import com.agentdemo007.session.model.StandardQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 步骤（第四层·{@code @Order(660)}，紧随 {@code ToolExecutionStep(650)}、先于 {@code ContextBuilder(700)}）。
 *
 * <p>串起完整 RAG 链路：召回（{@link Retriever} seam，dev 装配 {@link HybridRetriever} 向量+关键词融合；
 * 纯向量可用 {@link VectorRetriever}）→ 校验（{@link RetrievalValidator}，阈值/数量下限）
 * → 重排（{@link Reranker}，BM25-lite）→ 注入扫描（{@link RagInjectionScanner}）。纯净片段经
 * {@link RagFragment#displayText()} 抽取为 {@code List<String>} 写入 {@code context.ragFragments}
 * （§5.14 收口——不外泄 bespoke 结构到步骤间，仅文本 + 时效标注；历史片段隔离标注，§5.4.1），
 * 由 {@code ObjectiveDataLayer} 框定为单条 User 消息纳入客观数据层。
 *
 * <p>降级语义（§5.12 RAG 行"跳过 RAG 继续（不阻塞）"）：召回空 / 校验不达标 / 注入扫描清空 / 链路异常
 * 均降级 {@code Degrade(RAG_SKIP)}，标记 degraded 但继续推进，绝不短路、绝不阻塞（deg-004）。
 * 与入口注入短路分工：入口注入走 {@code ShortCircuit(INJECTION)} 零 LLM（deg-001），RAG 片段注入走静默过滤降级。
 *
 * <p>输入取 {@code standardQuery}（缺失回退 {@code rawInput}，与 {@code ToolExecutionStep} 一致）。
 */
@Component
@Order(660)
public class RagStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(RagStep.class);

    private final Retriever retriever;
    private final RetrievalValidator validator;
    private final Reranker reranker;
    private final RagInjectionScanner scanner;
    private final int topK;
    private final AgentMetrics metrics;

    public RagStep(Retriever retriever,
                   RetrievalValidator validator,
                   Reranker reranker,
                   RagInjectionScanner scanner,
                   @Value("${app.rag.top-k:5}") int topK) {
        this(retriever, validator, reranker, scanner, topK, AgentMetrics.NO_OP);
    }

    @Autowired
    public RagStep(Retriever retriever,
                   RetrievalValidator validator,
                   Reranker reranker,
                   RagInjectionScanner scanner,
                   @Value("${app.rag.top-k:5}") int topK,
                   AgentMetrics metrics) {
        this.retriever = retriever;
        this.validator = validator;
        this.reranker = reranker;
        this.scanner = scanner;
        this.topK = topK;
        this.metrics = metrics;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        // chit-chat 已由前置 KeywordTriageStep(@Order 150) 分诊 → 跳过 RAG（Proceed，非 Degrade）。
        // 闲聊无需知识库，跳过RAG是正常非降级——此前误报"降级·系统仍答·RAG_SKIP"的根因（deg-004
        // 适用于需要RAG却召回失败的意图；chit-chat 本就不该走RAG，跳过=正常Proceed）。
        if (context.intent() == Intent.CHIT_CHAT) {
            return new StepOutcome.Proceed();
        }
        String query = resolveQuery(context);
        try {
            List<RagFragment> candidates = retriever.retrieve(query, topK);
            List<RagFragment> validated = validator.validate(candidates);
            if (validated.isEmpty()) {
                return skipRag(context, "空召回或校验不达标");
            }
            List<RagFragment> reranked = reranker.rerank(query, validated);
            List<RagFragment> clean = scanner.scan(reranked);
            if (clean.isEmpty()) {
                return skipRag(context, "注入扫描清空全部片段");
            }
            List<String> texts = clean.stream().map(RagFragment::displayText).toList();
            context.setRagFragments(texts);
            // Phase 20 citation：来源标识 + 片段摘要（可追溯 ≠ 一定正确），与 ragFragments 一一对应。
            // source 在 RagFragment 数据载体层存活但 §5.14 曾在 displayText 边界丢弃；此处显式外泄为
            // 强类型 List<String> 收口（非 Map），供回答侧呈现来源 + 审计侧定位命中片段。
            List<String> citations = clean.stream()
                    .map(f -> "[来源: " + (f.source() != null ? f.source() : "未知") + "] " + f.displayText())
                    .toList();
            context.setRagCitations(citations);
            log.debug("RAG 填充 ragFragments：sessionId={} size={} sources={}",
                    context.sessionId(), texts.size(),
                    clean.stream().map(RagFragment::source).toList()); // 审计：命中 source 可追溯
            metrics.recordRag(true);
            return new StepOutcome.Proceed();
        } catch (Exception e) {
            log.warn("RAG 链路异常，跳过 RAG 不阻塞：sessionId={} reason={}",
                    context.sessionId(), e.getMessage()); // 审计
            return skipRag(context, "链路异常: " + e.getMessage());
        }
    }

    /** 降级 RAG_SKIP：标记场景与 degraded，返回 Degrade 继续（ragFragments 维持空）。 */
    private StepOutcome.Degrade skipRag(PipelineContext context, String reason) {
        context.markDegraded(DegradationScenario.RAG_SKIP);
        log.debug("RAG_SKIP：sessionId={} reason={}", context.sessionId(), reason);
        metrics.recordRag(false);
        return new StepOutcome.Degrade(DegradationScenario.RAG_SKIP);
    }

    private String resolveQuery(PipelineContext context) {
        StandardQuery sq = context.standardQuery();
        return (sq != null) ? sq.text() : context.rawInput();
    }
}
