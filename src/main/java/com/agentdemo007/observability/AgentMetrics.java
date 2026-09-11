package com.agentdemo007.observability;

import com.agentdemo007.common.degradation.DegradationScenario;
import com.agentdemo007.gateway.config.RouteRule;
import com.agentdemo007.intent.Intent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 指标埋点收口门面（Phase 15·全链路可观测）。
 *
 * <p>④统一收口：所有指标只经此门面记录——同 {@link com.agentdemo007.common.pipeline.StepOutcomeAuditor}
 * 之于审计，禁止各处散落 {@link MeterRegistry} 直接打点导致命名/标签漂移。每个记录方法产出一个
 * 稳定命名的 meter（counter/timer），标签维度由场景语义决定；后续各埋点点（intent/route/gateway/
 * rag/tool/hitl/mq/failover）统一经此门面。
 *
 * <p>②每步降级：MeterRegistry 由 Spring 装配（dev {@code SimpleMeterRegistry}，prod Prometheus），
 * 门面不感知后端；指标记录失败不抛（Micrometer 内部兜底），不影响主链路。{@link #NO_OP} 为不装配
 * 后端时的空实现（写入被丢弃的 {@link SimpleMeterRegistry}），供非 Spring 单测 / 禁用指标场景复用，
 * 永不外泄、永不为 null。
 */
@Component
public class AgentMetrics {

    /** 空实现：写入被丢弃的 SimpleMeterRegistry，供非 Spring 单测与编排器便利构造复用。 */
    public static final AgentMetrics NO_OP = new AgentMetrics(new SimpleMeterRegistry());

    static final String CHAT_REQUESTS = "agent.chat.requests";
    static final String DEGRADATION = "agent.degradation";
    static final String SCENARIO_TAG = "scenario";
    static final String PIPELINE_DURATION = "agent.pipeline.duration";
    static final String PIPELINE_OUTCOME = "agent.pipeline.outcome";
    static final String OUTCOME_TAG = "outcome";
    static final String NONE = "none";
    static final String INTENT = "agent.intent";
    static final String INTENT_TAG = "intent";
    static final String ROUTE = "agent.route";
    static final String ROUTE_TAG = "route";
    static final String MODEL_TAG = "model";
    static final String GATEWAY_CALL_DURATION = "agent.gateway.call.duration";
    static final String GATEWAY_CALLS = "agent.gateway.calls";
    static final String SUCCESS_TAG = "success";
    static final String RAG = "agent.rag";
    static final String HIT_TAG = "hit";
    static final String TOOL = "agent.tool";
    static final String TOOL_CIRCUIT_OPEN = "agent.tool.circuit.open";
    static final String TOOL_NAME_TAG = "tool";
    static final String HITL = "agent.hitl";
    static final String TRIGGERED_TAG = "triggered";
    static final String MQ = "agent.mq";
    static final String CHANNEL_TAG = "channel";
    static final String FAILOVER = "agent.failover";
    static final String TRUE = "true";
    static final String FALSE = "false";

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 接入层对话请求计数（POST /chat /chat/stream）。 */
    public void recordChatRequest() {
        registry.counter(CHAT_REQUESTS).increment();
    }

    /** 降级事件计数，按场景维度打标签（②每步降级：每个被降级的环节都经此记录）。 */
    public void recordDegradation(DegradationScenario scenario) {
        registry.counter(DEGRADATION, SCENARIO_TAG, scenario.name()).increment();
    }

    /** 流水线整体耗时（编排器 run() 起止包夹，毫秒）。 */
    public void recordPipelineDuration(long millis) {
        registry.timer(PIPELINE_DURATION).record(Duration.ofMillis(millis));
    }

    /**
     * 流水线终端产出计数，按 outcome + scenario 双标签。
     *
     * <p>编排器在三条返回路径（ok / degraded / shortCircuit）各调一次，每请求恰好一次。
     * scenario 为 null（OK 路径）时记 {@code "none"}，保证同 meter 标签键一致（避免 Prometheus
     * 标签基数不一致告警）。
     */
    public void recordOutcome(Outcome outcome, DegradationScenario scenario) {
        registry.counter(PIPELINE_OUTCOME,
                OUTCOME_TAG, outcome.tag(),
                SCENARIO_TAG, scenario != null ? scenario.name() : NONE).increment();
    }

    /** 意图识别计数（每次请求的识别结果，按意图维度打标签）。 */
    public void recordIntent(Intent intent) {
        registry.counter(INTENT, INTENT_TAG, intent.name()).increment();
    }

    /** 模型路由计数（按路由类型 + 选定模型双标签）。 */
    public void recordRoute(RouteRule.RouteType routeType, String modelId) {
        registry.counter(ROUTE, ROUTE_TAG, routeType.name(), MODEL_TAG, modelId).increment();
    }

    /** 网关模型调用：耗时 Timer + 成功/失败计数（success 维度标签）。 */
    public void recordModelCall(long millis, boolean success) {
        registry.timer(GATEWAY_CALL_DURATION).record(Duration.ofMillis(millis));
        registry.counter(GATEWAY_CALLS, SUCCESS_TAG, success ? TRUE : FALSE).increment();
    }

    /** RAG 命中/跳过计数（hit 维度：true=注入了片段，false=跳过）。 */
    public void recordRag(boolean hit) {
        registry.counter(RAG, HIT_TAG, hit ? TRUE : FALSE).increment();
    }

    /** 工具执行成功/失败计数（success 维度）。 */
    public void recordTool(boolean success) {
        registry.counter(TOOL, SUCCESS_TAG, success ? TRUE : FALSE).increment();
    }

    /**
     * 工具熔断开计数（Phase 17·T77）——per-tool 断路器 OPEN 时由 {@code ToolExecutionStep}
     * 记录，按 tool 维度打标签。区别于 {@link #recordTool(boolean)} 的成功/失败计数：
     * 熔断是工具持续不可用的跨请求累积状态，专供 P1 告警规则 {@code agent.tool.circuit.open>0}
     * 消费（§5.9 可观测），不与单次执行成功/失败混计。
     */
    public void recordToolCircuitOpen(String toolName) {
        registry.counter(TOOL_CIRCUIT_OPEN, TOOL_NAME_TAG, toolName).increment();
    }

    /** HITL 触发/放行计数（triggered 维度：true=转人工短路，false=放行）。 */
    public void recordHitl(boolean triggered) {
        registry.counter(HITL, TRIGGERED_TAG, triggered ? TRUE : FALSE).increment();
    }

    /** MQ 投递计数（channel=history/audit + success 双标签，§5.11/§5.12 收口）。 */
    public void recordMqPublish(String channel, boolean success) {
        registry.counter(MQ, CHANNEL_TAG, channel, SUCCESS_TAG, success ? TRUE : FALSE).increment();
    }

    /** 故障转移事件计数（outcome=success=成功转移 / exhausted=候选耗尽）。 */
    public void recordFailover(boolean exhausted) {
        registry.counter(FAILOVER, OUTCOME_TAG, exhausted ? "exhausted" : "success").increment();
    }

    /** 流水线终端产出类型（区分 ①话术短路 vs ②每步降级 vs 正常）。 */
    public enum Outcome {
        OK("ok"),
        DEGRADED("degraded"),
        SHORT_CIRCUIT("short_circuit");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }
}
