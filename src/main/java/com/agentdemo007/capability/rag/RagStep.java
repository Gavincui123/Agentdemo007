package com.agentdemo007.capability.rag;

import com.agentdemo007.capability.plan.RoutePlan;
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
import java.util.stream.Collectors;

/**
 * RAG 步骤（第四层·{@code @Order(660)}，紧随 {@code ToolExecutionStep(650)}、先于 {@code ContextBuilder(700)}）。
 *
 * <p>检索漏斗（真实 RAG 定案，dev/真库统一链序）：
 * <ol>
 *   <li><b>宽召回</b>（{@link Retriever} seam，{@code recall-dense} 条稠密预算；dev 装配
 *       {@link HybridRetriever}，真库装配 {@code ChromaHybridRetriever}——稠密余弦人人带置信度，
 *       BM25 命中提权）；</li>
 *   <li><b>knowledgeDomains 窄化</b>：routePlan 声明的知识域优先保留（domain=null 恒通过，全滤空回退原候选，
 *       补上 [[routeplan-design]] "knowledge_domains 缩范围留后"的缺口）；</li>
 *   <li><b>宽松粗滤</b>（仅池 &gt; top_n 将走重排时）：cosine &lt; prefilter 阈值丢弃——只杀确定垃圾省
 *       重排成本，不抢裁判的班（0.2~0.4 边际带完整留给 cross-encoder）；</li>
 *   <li><b>条件重排</b>（池 &gt; {@code rerank-top-n} 才调，≤ 则跳过——候选不超注入上限时重排只改顺序
 *       不裁剪成员，不值一次 API；远程重排写 {@code relevance}，本地兜底只排序）；</li>
 *   <li><b>置信度终闸</b>（{@link RetrievalValidator}，检索最终动作）：被重排的看
 *       {@code relevance >= rerank-min-score}，未重排的看 {@code cosine >= min-score}，BM25-only
 *       无余弦口径候选未经理裁决不得入上下文——<b>低置信知识绝不进 LLM</b>；</li>
 *   <li><b>注入截断</b>（≤ top_n）→ <b>注入扫描</b>（{@link RagInjectionScanner}）。</li>
 * </ol>
 * 纯净片段经 {@link RagFragment#displayText()} 抽取为 {@code List<String>} 写入
 * {@code context.ragFragments}（§5.14 收口；历史片段隔离标注 §5.4.1），由 {@code ObjectiveDataLayer}
 * 框定为单条 User 消息纳入客观数据层；citations 同步外泄供前端"参考来源"展示。
 *
 * <p>降级语义（§5.12 RAG 行"跳过 RAG 继续（不阻塞）"）：召回空 / 终闸不达标 / 注入扫描清空 / 链路异常
 * 均降级 {@code Degrade(RAG_SKIP)}，标记 degraded 但继续推进，绝不短路、绝不阻塞（deg-004）。
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
    private final int recallDense;
    private final int rerankTopN;
    private final double prefilterMinScore;
    private final AgentMetrics metrics;
    /** 知识库目录快照（[[kb-ingest-design]] 任务3 权限过滤；可选依赖 setter 注入——kb 模块未装配/
     * 单测直构时为 null，跳过过滤保持既有行为）。 */
    private volatile com.agentdemo007.capability.kb.KbCatalogService catalog;

    public RagStep(Retriever retriever,
                   RetrievalValidator validator,
                   Reranker reranker,
                   RagInjectionScanner scanner,
                   @Value("${app.rag.recall-dense:24}") int recallDense,
                   @Value("${app.rag.rerank-top-n:10}") int rerankTopN,
                   @Value("${app.rag.prefilter-min-score:0.2}") double prefilterMinScore) {
        this(retriever, validator, reranker, scanner, recallDense, rerankTopN, prefilterMinScore,
                AgentMetrics.NO_OP);
    }

    @Autowired
    public RagStep(Retriever retriever,
                   RetrievalValidator validator,
                   Reranker reranker,
                   RagInjectionScanner scanner,
                   @Value("${app.rag.recall-dense:24}") int recallDense,
                   @Value("${app.rag.rerank-top-n:10}") int rerankTopN,
                   @Value("${app.rag.prefilter-min-score:0.2}") double prefilterMinScore,
                   AgentMetrics metrics) {
        this.retriever = retriever;
        this.validator = validator;
        this.reranker = reranker;
        this.scanner = scanner;
        this.recallDense = recallDense;
        this.rerankTopN = rerankTopN;
        this.prefilterMinScore = prefilterMinScore;
        this.metrics = metrics;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        // routePlan 契约（2026-09-18 用户裁决升级）：routePlan 是本轮能力决策契约，下游恒服从——
        // needsRag=false 对<b>所有 source</b> 生效（含 DETERMINISTIC_FALLBACK：收敛层产出与基线值
        // 同为确定性决策，不再是"noop 候选不采信"年代；典型=售后澄清/衔接/确认终态，主链 RAG 注入
        // 从不被消费）。needsRag=true 时：LLM 候选走漏斗；兜底候选保留旧意图逻辑（闲聊跳过）。
        RoutePlan rp = context.routePlan();
        if (rp != null && !rp.needsRag()) {
            return new StepOutcome.Proceed(); // routePlan 契约：无需 RAG（正常跳过，非降级）
        }
        if (rp == null || rp.source() != RoutePlan.Source.LLM_WITH_POLICY_CONSTRAINTS) {
            if (context.intent() == Intent.CHIT_CHAT) {
                // chit-chat 已由前置 KeywordTriageStep(@Order 150) 分诊 → 跳过 RAG（Proceed，非 Degrade）。
                // 闲聊无需知识库，跳过RAG是正常非降级——此前误报"降级·系统仍答·RAG_SKIP"的根因（deg-004
                // 适用于需要RAG却召回失败的意图；chit-chat 本就不该走RAG，跳过=正常Proceed）。
                return new StepOutcome.Proceed();
            }
        }
        String query = resolveQuery(context);
        try {
            // ① 宽召回（topK 参数 = 稠密召回预算；融合池不截断，人人可携带检索置信度）
            List<RagFragment> pool = retriever.retrieve(query, recallDense);
            int recalled = pool.size();
            // ①′ 知识库权限过滤（[[kb-ingest-design]] 任务3）：命名空间/权限/版本活性过滤——
            //     无权片段在进漏斗前出局（不占粗滤/重排/注入名额）；私有片段对无权主体如同不存在。
            com.agentdemo007.capability.kb.KbCatalogService kbCatalog = this.catalog;
            if (kbCatalog != null) {
                List<RagFragment> readable = kbCatalog.filterReadable(pool, context.userId());
                if (readable.size() != pool.size()) {
                    log.debug("知识库权限过滤：sessionId={} userId={} {}→{} 条（无权/下架片段出局）",
                            context.sessionId(), context.userId(), pool.size(), readable.size());
                    pool = readable;
                }
            }
            if (log.isDebugEnabled()) {
                long cosineCount = pool.stream().filter(RagFragment::cosineScored).count();
                RagFragment top1 = pool.isEmpty() ? null : pool.get(0);
                log.debug("宽召回：sessionId={} query='{}' {}条（余弦口径{} BM25-only{}）top1={}",
                        context.sessionId(), abbreviate(query), recalled, cosineCount, recalled - cosineCount,
                        top1 != null ? describeFragment(top1) : "无");
            }
            // ② knowledgeDomains 窄化（domain=null 恒通过；全滤空回退原候选，不新增降级场景）
            pool = narrowByKnowledgeDomains(rp, pool);
            int afterNarrow = pool.size();
            if (afterNarrow != recalled) {
                log.debug("知识域窄化：sessionId={} {}→{} 条（routePlan 声明域优先，domain=null 恒通过）",
                        context.sessionId(), recalled, afterNarrow);
            }
            // ③ 宽松粗滤（仅将走重排时）：只杀确定垃圾省重排成本，边际带留给 cross-encoder
            if (pool.size() > rerankTopN) {
                List<RagFragment> beforePrefilter = pool;
                pool = pool.stream()
                        .filter(f -> !f.cosineScored() || f.score() >= prefilterMinScore)
                        .toList();
                if (pool.size() != beforePrefilter.size()) {
                    String dropped = beforePrefilter.stream()
                            .filter(f -> f.cosineScored() && f.score() < prefilterMinScore)
                            .map(f -> f.source() + "=" + String.format("%.3f", f.score()))
                            .collect(Collectors.joining(", "));
                    log.debug("粗滤丢弃（cosine<{}）：sessionId={} 出局=[{}]",
                            prefilterMinScore, context.sessionId(), dropped);
                }
            }
            int afterPrefilter = pool.size();
            // ④ 条件重排：池 > top_n 才调（≤ top_n 时重排只改顺序不裁剪，跳过 API）；
            //    远程重排写 relevance，本地兜底/降级只排序（不覆写检索置信度）
            if (pool.size() > rerankTopN) {
                pool = reranker.rerank(query, pool);
                log.debug("条件重排：sessionId={} 池 {}→{} 条（远程写 relevance；本地兜底只排序）",
                        context.sessionId(), afterPrefilter, pool.size());
            }
            // ⑤ 置信度终闸（检索最终动作）：被重排看 relevance，未重排看 cosine；BM25-only 无口径不放过
            //    逐片段裁决明细（含 score/relevance vs 阈值与淘汰原因）走 DEBUG；实际闸门仍以 validate 为准
            if (log.isDebugEnabled()) {
                for (RetrievalValidator.GateRecord r : validator.decide(pool)) {
                    log.debug("终闸裁决：sessionId={} {} → {}（{}）", context.sessionId(),
                            describeFragment(r.fragment()), r.passed() ? "通过" : "拦截", r.reason());
                }
            }
            List<RagFragment> gated = validator.validate(pool);
            if (gated.isEmpty()) {
                return skipRag(context, "空召回或置信度终闸不达标（召回=" + recalled + " 终闸候选=" + pool.size() + "）");
            }
            // ⑥ 注入截断（≤ top_n）→ 注入扫描
            List<RagFragment> bounded = (gated.size() > rerankTopN)
                    ? gated.subList(0, rerankTopN) : gated;
            if (bounded.size() < gated.size()) {
                log.debug("注入截断：sessionId={} {}→{} 条，出局=[{}]", context.sessionId(),
                        gated.size(), bounded.size(),
                        gated.subList(bounded.size(), gated.size()).stream()
                                .map(RagFragment::source).collect(Collectors.joining(", ")));
            }
            List<RagFragment> clean = scanner.scan(bounded);
            if (clean.isEmpty()) {
                return skipRag(context, "注入扫描清空全部片段（" + bounded.size() + " 条全部命中注入词库）");
            }
            List<String> texts = clean.stream().map(RagFragment::displayText).toList();
            context.setRagFragments(texts);
            // Phase 20 citation：来源标识 + 片段摘要（可追溯 ≠ 一定正确），与 ragFragments 一一对应。
            // source 在 RagFragment 数据载体层存活但 §5.14 曾在 displayText 边界丢弃；此处显式外泄为
            // 强类型 List<String> 收口（非 Map），供回答侧呈现来源 + 审计侧定位命中片段 + 前端"参考来源"展示。
            List<String> citations = clean.stream()
                    .map(f -> "[来源: " + (f.source() != null ? f.source() : "未知") + "] " + f.displayText())
                    .toList();
            context.setRagCitations(citations);
            // 漏斗总览一行（INFO 审计口径）：召回→窄化→粗滤→终闸→扫描各段存量 + 最终注入来源；
            // 各段淘汰明细（含置信度与原因）在 DEBUG 的 宽召回/窄化/粗滤/重排/终闸裁决/截断 各行。
            log.info("RAG注入完成：sessionId={} query='{}' 召回={} 窄化→{} 粗滤→{} 终闸→{} 扫描→{} 注入={} sources=[{}]",
                    context.sessionId(), abbreviate(query), recalled, afterNarrow, afterPrefilter,
                    gated.size(), bounded.size(), clean.size(),
                    clean.stream().map(RagFragment::source).collect(Collectors.joining(", ")));
            metrics.recordRag(true);
            return new StepOutcome.Proceed();
        } catch (Exception e) {
            log.warn("RAG 链路异常，跳过 RAG 不阻塞：sessionId={} reason={}",
                    context.sessionId(), e.getMessage()); // 审计
            return skipRag(context, "链路异常: " + e.getMessage());
        }
    }

    /** 片段观测摘要（终闸/召回明细日志用）：source + 置信度口径（cosine 分或 BM25 分）+ relevance + 域。 */
    private static String describeFragment(RagFragment f) {
        return String.format("[source=%s score=%s relevance=%s cosine=%s domain=%s]",
                f.source(),
                f.cosineScored() ? String.format("%.3f", f.score()) : String.format("%.2f(BM25)", f.score()),
                f.relevance() != null ? String.format("%.3f", f.relevance()) : "-",
                f.cosineScored(), f.domain());
    }

    /** 查询串截断（日志口径防刷屏，32 字）。 */
    private static String abbreviate(String s) {
        return (s == null || s.length() <= 32) ? s : s.substring(0, 32) + "…";
    }

    /**
     * knowledgeDomains 窄化：routePlan 声明的知识域命中优先（保序）；domain=null 恒通过
     * （dev 种子/无域片段不受影响）；全部滤空 → 回退原候选（窄化只提纯不造空，不新增降级场景）。
     */
    private static List<RagFragment> narrowByKnowledgeDomains(RoutePlan rp, List<RagFragment> pool) {
        List<String> domains = (rp != null) ? rp.knowledgeDomains() : null;
        if (domains == null || domains.isEmpty() || pool.isEmpty()) {
            return pool;
        }
        List<RagFragment> matched = pool.stream()
                .filter(f -> f.domain() == null || domains.contains(f.domain()))
                .toList();
        return matched.isEmpty() ? pool : matched;
    }

    /**
     * 降级 RAG_SKIP：标记场景与 degraded，返回 Degrade 继续（ragFragments 维持空）。INFO 审计口径——跳过原因必须可见。
     *
     * <p>[[refusal-design]]：置位 {@code context.groundingMiss}——"本轮需要知识支撑但漏斗终态零片段"
     * 是拒答裁决的关键事实，消费者 {@code RefusalGateStep}@690（strict 短路/prompt 注入约束）与
     * {@code SystemAnchorLayer}（Runtime 块拒答约束）。降级语义不变（仍 Degrade 不阻塞），
     * 仅<b>附加</b>拒答信号；闲聊/计划免 RAG 的正常跳过不经此（不置位）。
     */
    private StepOutcome.Degrade skipRag(PipelineContext context, String reason) {
        context.setGroundingMiss(true);
        context.markDegraded(DegradationScenario.RAG_SKIP);
        log.info("RAG_SKIP（不阻塞继续）：sessionId={} reason={}", context.sessionId(), reason);
        metrics.recordRag(false);
        return new StepOutcome.Degrade(DegradationScenario.RAG_SKIP);
    }

    private String resolveQuery(PipelineContext context) {
        StandardQuery sq = context.standardQuery();
        return (sq != null) ? sq.text() : context.rawInput();
    }

    /**
     * 注入知识库目录快照（[[kb-ingest-design]] 任务3）：可选依赖 setter 注入——避免 kb↔rag
     * 构造环（kb 录入依赖 VectorStore/Retriever 同包 seam，RagStep 反向依赖目录快照），
     * 同时保持既有单测直构 RagStep 的构造器签名零改动。null（kb 未装配）→ 跳过过滤。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKbCatalog(com.agentdemo007.capability.kb.KbCatalogService kbCatalog) {
        this.catalog = kbCatalog;
    }
}
