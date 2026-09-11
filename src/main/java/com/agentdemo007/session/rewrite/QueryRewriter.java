package com.agentdemo007.session.rewrite;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.gateway.llm.ChatLlmService;
import com.agentdemo007.intent.Intent;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.model.StandardQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户问题改写步骤（第二层·{@code @Order(300)}）。
 *
 * <p>结合会话历史上下文，将指代/省略/口语化问题改写为自足的标准化 Query（§5.2.2），
 * 写入 {@code context.standardQuery}，供意图识别（Phase 7）消费。
 *
 * <p>Phase 20 约束改写：产出**只补全不替换**——改写须保留原问题原意（不丢弃用户原话），
 * 只补关键词/商品/活动名/时间线，**不下业务结论**（不替用户判定意图归属/订单状态/业务决策）。
 * 补全经 {@link QueryEnricher} 抽取精确词/时间线写入 {@code context.queryEnrichment}
 * （强类型槽，**不覆盖** standardQuery；原查询经 {@code rawInput} 保留不丢）。
 *
 * <p>收口：出站 LLM 调用只经 {@link ChatLlmService}，走小模型通道（{@link Intent#CHIT_CHAT}），
 * 不在此直连引擎（§9.11）。
 *
 * <p>②每步降级：改写 LLM 失败/空输出 → 回退 {@code rawInput} 包装为 {@link StandardQuery} 继续
 * （不阻塞，§5.12 行为级降级——该行无话术、不短路，仅回退原问题继续推进，补全槽从原问题抽取）。
 */
@Component
@Order(300)
public class QueryRewriter implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private final ChatLlmService llm;
    private final QueryEnricher enricher;

    public QueryRewriter(ChatLlmService llm) {
        this(llm, new QueryEnricher());
    }

    @Autowired
    public QueryRewriter(ChatLlmService llm, QueryEnricher enricher) {
        this.llm = llm;
        this.enricher = enricher;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        String rawInput = context.rawInput();
        // chit-chat 已由前置 KeywordTriageStep(@Order 150) 分诊 → 直用原问题，不调改写小模型（零 LLM）。
        // 闲聊无需指代消解/上下文补全，改写只会浪费一轮小模型调用（此前"你好"也走改写的根因）。
        if (context.intent() == Intent.CHIT_CHAT) {
            context.setStandardQuery(StandardQuery.of(rawInput));
            context.setQueryEnrichment(enricher.enrich(rawInput));
            return new StepOutcome.Proceed();
        }
        try {
            String prompt = buildPrompt(context.history(), rawInput);
            String rewrite = llm.decide(prompt);
            if (rewrite == null || rewrite.isBlank()) {
                log.debug("改写输出为空，回退原问题：sessionId={}", context.sessionId());
                context.setStandardQuery(StandardQuery.of(rawInput));
                context.setQueryEnrichment(enricher.enrich(rawInput));
                return new StepOutcome.Proceed();
            }
            String trimmed = rewrite.trim();
            context.setStandardQuery(StandardQuery.of(trimmed));
            context.setQueryEnrichment(enricher.enrich(trimmed));
            return new StepOutcome.Proceed();
        } catch (Exception e) {
            log.debug("改写 LLM 调用失败，回退原问题（不阻塞）：sessionId={} reason={}",
                    context.sessionId(), e.getMessage());
            context.setStandardQuery(StandardQuery.of(rawInput));
            context.setQueryEnrichment(enricher.enrich(rawInput));
            return new StepOutcome.Proceed();
        }
    }

    private String buildPrompt(List<ChatMessage> history, String rawInput) {
        String transcript = history.isEmpty()
                ? "（无历史，本轮为首句）"
                : history.stream()
                        .map(m -> "[" + label(m) + "] " + m.content())
                        .collect(Collectors.joining("\n"));
        return "你是问题改写器。根据以下对话历史，把用户本轮的口语化/指代/省略问题"
                + "改写为一个自足、可独立理解的标准查询。约束：必须保留用户原问题原意"
                + "（不可丢弃/篡改用户原话），只补充必要的上下文（关键词/商品/活动名/时间线）使指代可消解；"
                + "严禁替用户下业务结论（不得判定订单状态、意图归属或业务决策）。"
                + "只输出改写后的查询，不要附加说明：\n"
                + "历史：\n" + transcript + "\n用户本轮问题：" + rawInput;
    }

    private String label(ChatMessage m) {
        if (m instanceof ChatMessage.User) return "用户";
        if (m instanceof ChatMessage.Ai) return "助手";
        if (m instanceof ChatMessage.System) return "系统";
        if (m instanceof ChatMessage.ToolResult) return "工具";
        return "消息";
    }
}
