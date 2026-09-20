package com.agentdemo007.session;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.common.exception.SessionCacheException;
import com.agentdemo007.common.pipeline.PipelineContext;
import com.agentdemo007.common.pipeline.PipelineStep;
import com.agentdemo007.common.pipeline.StepOutcome;
import com.agentdemo007.session.cache.SessionCacheService;
import com.agentdemo007.session.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 会话加载步骤（第二层·会话缓存基础——首个真实 {@link PipelineStep}）。
 *
 * <p>落地收口契约：经 {@link PipelineContext} 读写、返回 {@link StepOutcome}。
 * <ul>
 *   <li>正常 → 拉取 Redis 会话历史回填 {@code context.history()} + {@link StepOutcome.Proceed}。</li>
 *   <li>Redis 故障（{@link SessionCacheException}）→ <b>降级单轮模式继续</b>
 *       {@link StepOutcome.Degrade}{@code (SESSION_DOWN)}：history 置空、标记 degraded，轮次照常推进。
 *       <b>2026-09-17 定案（实测 Redis 抖动整轮被杀）</b>：Redis 是缓存不是真相源（对话已异步落
 *       MySQL chat_turn），缓存故障不杀轮——代价是无历史时多轮指代类问题可能失准（审计可见 degraded）。
 *       原策略 ShortCircuit（§5.12 初版）按本定案退役，eval deg-003 同步改 DEGRADE。</li>
 * </ul>
 */
public class SessionLoadStep implements PipelineStep {

    private static final Logger log = LoggerFactory.getLogger(SessionLoadStep.class);

    private final SessionCacheService cacheService;

    public SessionLoadStep(SessionCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public StepOutcome process(PipelineContext context) {
        try {
            List<ChatMessage> history = cacheService.load(context.sessionId());
            context.setHistory(history);
            return new StepOutcome.Proceed();
        } catch (SessionCacheException e) {
            log.warn("会话缓存故障，降级单轮模式继续（不杀轮）：sessionId={} reason={}",
                    context.sessionId(), e.getMessage());
            context.setHistory(List.of());
            return new StepOutcome.Degrade(DegradationScenario.SESSION_DOWN);
        }
    }
}
