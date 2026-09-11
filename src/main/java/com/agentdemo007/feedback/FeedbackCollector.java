package com.agentdemo007.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.function.Supplier;

/**
 * 反馈采集器（Phase 15·T67 微调闭环数据层入口）。
 *
 * <p>把 {@link FeedbackRequest}（在线反馈 + 当轮对话上下文）组装为 {@link TrainingSample} 落入
 * {@link OfflineDataPool}，供 T68 {@code FineTuningPipeline} 拉取训练。时钟可注入保证 collectedAt 确定性。
 *
 * <p>②每步降级：落池（含组装）异常吞而不抛——反馈是副信道，绝不影响主链路；采集失败仅记日志 + 返回 false。
 *
 * <p>装配对齐 {@code TokenBudgetChecker}：plain class + @Bean 工厂（{@code FeedbackConfig}），
 * 非 {@code @Component}（避免为可注入时钟强加 Supplier bean）。
 */
public class FeedbackCollector {

    private static final Logger log = LoggerFactory.getLogger(FeedbackCollector.class);

    private final OfflineDataPool pool;
    private final Supplier<OffsetDateTime> clock;

    public FeedbackCollector(OfflineDataPool pool) {
        this(pool, OffsetDateTime::now);
    }

    public FeedbackCollector(OfflineDataPool pool, Supplier<OffsetDateTime> clock) {
        this.pool = pool;
        this.clock = clock;
    }

    /**
     * 采集一条反馈：组装样本 → 落池。
     *
     * @param req 反馈请求（由调用方校验非空字段）
     * @return 落池成功返回 true；失败（存储不可达 / 入参异常）返回 false（②降级不抛）
     */
    public boolean collect(FeedbackRequest req) {
        if (req == null) {
            return false;
        }
        try {
            TrainingSample sample = new TrainingSample(req.traceId(), req.sessionId(),
                    req.prompt(), req.reply(), req.label(), req.comment(), clock.get());
            pool.store(sample);
            return true;
        } catch (Exception e) {
            log.warn("反馈采集落池失败（②降级不抛）：traceId={} reason={}",
                    req.traceId(), e.getMessage());
            return false;
        }
    }
}
