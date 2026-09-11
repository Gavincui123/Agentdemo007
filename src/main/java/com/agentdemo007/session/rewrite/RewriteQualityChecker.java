package com.agentdemo007.session.rewrite;

import com.agentdemo007.access.PromptSanitizer;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.model.StandardQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 改写质量校验步骤（第二层·{@code @Order(400)}，紧随 {@code QueryRewriter}）。
 *
 * <p>对改写产出做完整性/忠实度校验（§5.2.3）：检测被注入隔离定界符污染、空、长度失控等
 * 明显失真情形；不达标回退 {@code rawInput}，避免下游基于失真改写做意图识别（②每步降级，不阻塞）。
 *
 * <p>Phase 20 约束改写增"未新增业务结论"守卫：改写不得替用户下业务结论——若改写凭空断言
 * 业务状态（已发货/已退款/已到账/已完成 等）而**用户原问题未提及**该状态 → 视为失真回退原问题。
 * 用户原话提及该状态（询问/引用）时改写保留之不算新增结论 → 通过。
 *
 * <p>语义级忠实度（深层语义漂移）需 LLM 判定，延后至小模型流式接入后扩展；
 * 收口出口形状不变（仍 Proceed + 可能回退 rawInput）。
 */
@Component
@Order(400)
public class RewriteQualityChecker implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(RewriteQualityChecker.class);

    private final QueryEnricher enricher;

    /** 既有无参构造（旧测试零改动）：用默认补全器。 */
    public RewriteQualityChecker() {
        this(new QueryEnricher());
    }

    /** Spring 注入：复用容器内 QueryEnricher（@Component），回退时从 rawInput 重抽补全槽。 */
    @Autowired
    public RewriteQualityChecker(QueryEnricher enricher) {
        this.enricher = enricher;
    }

    /** 改写长度硬上限，超过视为失控产出。 */
    private static final int MAX_REWRITE_LEN = 2000;

    /** 业务状态断言标记——改写不得凭空新增（用户原问题未提及时视为替用户下结论）。 */
    private static final List<String> BUSINESS_CONCLUSION_MARKERS = List.of(
            "已发货", "已退款", "已到账", "已收货", "已取消", "已完成", "已支付",
            "状态为", "订单状态是", "退款已成功");

    @Override
    public StepOutcome process(PipelineContext context) {
        StandardQuery rewrite = context.standardQuery();
        String rawInput = context.rawInput();
        if (rewrite == null || !passes(rewrite, rawInput)) {
            log.debug("改写质量校验不达标，回退原问题：sessionId={}", context.sessionId());
            context.setStandardQuery(StandardQuery.of(rawInput));
            // 回退须同步重置补全槽（从 rawInput 重抽）——否则残留坏改写抽出的词，
            // 与回退后的 standardQuery 不一致，误导 ③环节测评 queryEnrichmentContains 对照
            context.setQueryEnrichment(enricher.enrich(rawInput));
        }
        return new StepOutcome.Proceed();
    }

    private boolean passes(StandardQuery rewrite, String rawInput) {
        String text = rewrite.text();
        if (text == null || text.isBlank()) {
            return false;
        }
        // 被注入隔离定界符污染：LLM 回显了包裹标记，视为失真
        if (text.contains(PromptSanitizer.OPEN) || text.contains(PromptSanitizer.CLOSE)) {
            return false;
        }
        // 长度失控
        if (text.length() > MAX_REWRITE_LEN) {
            return false;
        }
        // 相对原问题异常膨胀（原问题非平凡时）
        if (rawInput != null && rawInput.length() > 5
                && text.length() > rawInput.length() * 10) {
            return false;
        }
        // Phase 20：未新增业务结论（改写断言了原问题未提及的业务状态 → 替用户下结论 → 失真）
        if (introducesBusinessConclusion(text, rawInput)) {
            return false;
        }
        return true;
    }

    /** 改写是否凭空新增业务状态结论（原问题未提及该状态）。 */
    private static boolean introducesBusinessConclusion(String rewrite, String rawInput) {
        if (rawInput == null) {
            return false; // 无原问题可比，交由其它检查兜底
        }
        for (String marker : BUSINESS_CONCLUSION_MARKERS) {
            if (rewrite.contains(marker) && !rawInput.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
