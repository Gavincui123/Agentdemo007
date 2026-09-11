package com.agentdemo007.observability;

import com.agentdemo007.common.response.UnifiedResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可观测端点（Phase 19·T99）。
 *
 * <p>{@code GET /api/obs/summary}：返回实时可观测快照（{@link AgentMetrics} 稳定命名计数器 +
 * 模型/HITL/会话结构性计数），经 {@link ObservabilitySummary} 强类型对外收口（④统一收口），
 * 供运维台展示。
 *
 * <p>采集器读 {@link io.micrometer.core.instrument.MeterRegistry} 计数器为"进程累计当下值"
 * （dev {@code SimpleMeterRegistry} 内存计数，无 Prometheus 后端），不缓存、不持久化——每次请求实时聚合。
 * 空指标→0（②每步降级：端点恒可用，不抛）。鉴权在 T103 收口（{@code /api/obs/*} 应受保护，此处先开放便于联调）。
 */
@RestController
@RequestMapping("/api/obs")
public class ObservabilityController {

    private final ObservabilitySummaryCollector collector;

    public ObservabilityController(ObservabilitySummaryCollector collector) {
        this.collector = collector;
    }

    @GetMapping("/summary")
    public UnifiedResponse summary() {
        return UnifiedResponse.success(collector.summary());
    }
}
