package com.agentdemo007.observability;

import com.agentdemo007.capability.hitl.HumanTicketService;
import com.agentdemo007.gateway.config.ModelConfigCenter;
import com.agentdemo007.persistence.repository.ChatTurnRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可观测快照采集器（Phase 19·T99）。
 *
 * <p>从实时 {@link MeterRegistry}（与 {@link AgentMetrics} 同实例的稳定命名计数器）+ 模型/HITL/会话
 * 结构性计数 派生强类型 {@link ObservabilitySummary}（④收口：强类型字段，非 Map 散漏）。
 *
 * <p><b>同包访问 {@link AgentMetrics} 的稳定命名常量</b>（{@code CHAT_REQUESTS}/{@code DEGRADATION}/
 * {@code SCENARIO_TAG}/{@code PIPELINE_OUTCOME}/{@code OUTCOME_TAG}/{@code RAG}/{@code HIT_TAG}/
 * {@code TOOL}/{@code SUCCESS_TAG}/{@code HITL}/{@code TRIGGERED_TAG}/{@code FAILOVER}/
 * {@code TRUE}/{@code FALSE} 与 {@code Outcome.tag()}），使采集端的 meter 名/标签键/标签值
 * 与埋点端永不漂移——收口的双向一致：埋点改名则采集编译期即报错。
 *
 * <p>计数器读法（②每步降级：未埋点不抛、空指标→0）：
 * <ul>
 *   <li>{@code counterTotal}：某 meter 名下全部 counter 聚合（跨所有标签组合），{@code find().counters()} 空→0。</li>
 *   <li>{@code tagged}：单标签精确匹配，{@code find().tag().counter()} 返回 null→0。</li>
 *   <li>{@code counterByTag}：按标签维度分桶，空→空 Map（非 null）。</li>
 * </ul>
 *
 * <p>非 Spring 单测可直构造（与 {@code AlertRuleEvaluator} 同形：plain class + @Component 双兼容，
 * 测试直传真实/替身协作者，不依赖容器）。
 */
@Component
public class ObservabilitySummaryCollector {

    private final MeterRegistry meterRegistry;
    private final ModelConfigCenter configCenter;
    private final HumanTicketService ticketService;
    private final ChatTurnRepository turnRepository;

    public ObservabilitySummaryCollector(MeterRegistry meterRegistry,
                                         ModelConfigCenter configCenter,
                                         HumanTicketService ticketService,
                                         ChatTurnRepository turnRepository) {
        this.meterRegistry = meterRegistry;
        this.configCenter = configCenter;
        this.ticketService = ticketService;
        this.turnRepository = turnRepository;
    }

    /**
     * 派生可观测快照（每次请求实时聚合，不缓存、不持久化）。
     *
     * <p>dev {@code SimpleMeterRegistry} 内存计数为"进程累计当下值"；prod Prometheus 后端
     * 由其自身聚合。空指标/空注册表/无工单→对应字段 0 或空 Map，端点恒可用不抛。
     */
    public ObservabilitySummary summary() {
        return new ObservabilitySummary(
                counterTotal(AgentMetrics.CHAT_REQUESTS),
                counterTotal(AgentMetrics.DEGRADATION),
                counterByTag(AgentMetrics.DEGRADATION, AgentMetrics.SCENARIO_TAG),
                tagged(AgentMetrics.PIPELINE_OUTCOME, AgentMetrics.OUTCOME_TAG, AgentMetrics.Outcome.OK.tag()),
                tagged(AgentMetrics.PIPELINE_OUTCOME, AgentMetrics.OUTCOME_TAG, AgentMetrics.Outcome.DEGRADED.tag()),
                tagged(AgentMetrics.PIPELINE_OUTCOME, AgentMetrics.OUTCOME_TAG, AgentMetrics.Outcome.SHORT_CIRCUIT.tag()),
                tagged(AgentMetrics.RAG, AgentMetrics.HIT_TAG, AgentMetrics.TRUE),
                tagged(AgentMetrics.RAG, AgentMetrics.HIT_TAG, AgentMetrics.FALSE),
                tagged(AgentMetrics.TOOL, AgentMetrics.SUCCESS_TAG, AgentMetrics.FALSE),
                tagged(AgentMetrics.HITL, AgentMetrics.TRIGGERED_TAG, AgentMetrics.TRUE),
                tagged(AgentMetrics.FAILOVER, AgentMetrics.OUTCOME_TAG, "exhausted"),
                configCenter.registry().all().size(),
                configCenter.registry().enabled().size(),
                ticketService.pendingTickets().size(),
                turnRepository.count(),
                turnRepository.countByDegradedTrue()
        );
    }

    /** 聚合某 meter 名下全部 counter 的计数值（跨所有标签组合）。空→0。 */
    private long counterTotal(String name) {
        return (long) meterRegistry.find(name).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    /** 单标签精确匹配的 counter 计数值。无匹配 counter→0（②每步降级）。 */
    private long tagged(String name, String tagKey, String tagValue) {
        Counter c = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0L : (long) c.count();
    }

    /** 按某标签维度分桶聚合计数（键=标签值，值=计数）。无 counter→空 Map。 */
    private Map<String, Long> counterByTag(String name, String tagKey) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Counter c : meterRegistry.find(name).counters()) {
            String v = c.getId().getTag(tagKey);
            if (v != null) {
                map.merge(v, (long) c.count(), Long::sum);
            }
        }
        return map;
    }
}
