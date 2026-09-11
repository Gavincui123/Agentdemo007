package com.agentdemo007.session.router;

import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.model.ChatMessage;
import com.agentdemo007.session.summary.SummaryHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 会话路由步骤（第二层·{@code @Order(200)}，紧随 {@code SessionLoadStep}）。
 *
 * <p>依据已装载的会话历史决策命中/新建（§5.2.1）：
 * <ul>
 *   <li>命中（{@code context.history()} 非空）→ 历史已由 {@code SessionLoadStep} 回填，直接 Proceed。</li>
 *   <li>新建（历史为空）→ 触发 {@link SummaryHook} 生成会话摘要锚点写入 {@code context.summary}，
 *       作为后续上下文锚点；Hook 不可用（empty）时无锚点继续推进（②每步降级，不阻塞）。</li>
 * </ul>
 *
 * <p>说明：缓存拉取（命中路径的 Redis 读）由前置 {@code SessionLoadStep} 收口；
 * 本步骤只承载路由决策与摘要触发，职责不重叠（§5.14 单一职责 + 收口）。
 */
@Component
@Order(200)
public class SessionRouter implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(SessionRouter.class);

    private final SummaryHook summaryHook;

    public SessionRouter(SummaryHook summaryHook) {
        this.summaryHook = summaryHook;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        if (context.history().isEmpty()) {
            // 新建会话：触发摘要 Hook 生成上下文锚点
            Optional<String> anchor = summaryHook.summarize(context.history(), context.rawInput());
            if (anchor.isPresent()) {
                context.setSummary(anchor.get());
                log.debug("新建会话摘要锚点已写入：sessionId={} summary={}", context.sessionId(), anchor.get());
            } else {
                log.debug("新建会话摘要不可用，跳过锚点继续：sessionId={}", context.sessionId());
            }
        }
        // 命中会话：历史已回填，直接推进
        return new StepOutcome.Proceed();
    }
}
