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
 *   <li>Redis 故障（{@link SessionCacheException}）→
 *       {@link StepOutcome.ShortCircuit}{@code (SESSION_DOWN)} 话术短路（§5.12 + eval/degradation.json deg-003），
 *       由 {@code PipelineOrchestrator} 收口为话术 HTTP 200，不抛 5xx、零 LLM。</li>
 * </ul>
 *
 * <p>说明：SESSION_DOWN 取短路而非降级，与已批准的 §5.12 表 + eval deg-003 一致
 * （会话历史缺失时返回"服务繁忙"话术，避免无上下文的盲答）。如需"跳过历史继续单轮"的韧性策略，
 * 改为 {@code Degrade(SESSION_DOWN)} 并同步更新 eval 即可——收口出口形状不变。
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
            log.warn("会话缓存故障，触发 SESSION_DOWN 话术短路：sessionId={} reason={}",
                    context.sessionId(), e.getMessage());
            return new StepOutcome.ShortCircuit(DegradationScenario.SESSION_DOWN);
        }
    }
}
