---
name: phase15-t71-alert-design
description: Phase 15 T71 分级告警：AlertLevel(P0/P1/p2)+Comparison(GT/LT 配置可序列化)+AlertRule/Alert record+AlertChannel seam(NO_OP)+AlertRuleEvaluator(plain class+注入时钟，evaluate(Map)纯函数+best-effort send②降级不丢告警)
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-07T12:00:22.036Z
---

Phase 15·T71 分级告警（`com.agentdemo007.observability.alert` 包）。

**核心：** `AlertRuleEvaluator.evaluate(Map<String,Double> metrics)` 逐规则取 `rule.metric()` 值（缺失→跳过不误报），`Comparison.fires(value,threshold)` 命中→建 `Alert`→best-effort `AlertChannel.send`（②降级：通道异常 log.warn 不抛、不丢返回值、不影响后续规则）→收集返回 fired 列表。

**类型：**
- `AlertLevel` enum P0/P1/P2（致命/高/中）。
- `Comparison` enum GT/LT，`fires(value,threshold)` 抽象方法——配置友好可 JSON 序列化（未来 Nacos 热加载规则）。
- `AlertRule` record(name,level,metric,comparison,threshold,message) 强类型 ④收口。
- `Alert` record(ruleName,level,actualValue,threshold,message,firedAt)。
- `AlertChannel` @FunctionalInterface `send(Alert)` + `NO_OP` dev 默认。

**设计决策：**
- 输入 `Map<String,Double>`（指标快照本质 name→value bag，④"非 Map"原则仅约束步骤间结构，不约束指标快照）。评估器对指标来源无感——真实 MeterRegistry 派生快照由未来定时任务/可观测面板注入。
- plain class + @Bean 工厂 + 注入式 `Supplier<OffsetDateTime>` 时钟（匹配 TokenBudgetChecker/FeedbackCollector 模式）。
- **避 Spring `List<X>` 注入歧义：** 不注册 `List<AlertRule>` bean（Spring 按"所有 X bean"收集→空），标准 P0/P1/P2 规则集由 AlertConfig `standardRules()` 私有静态方法内联传入评估器工厂。与 T70 EvalSuite 用 wrapper record 避 `List<String>` 歧义同因不同策（EvalSuite 需对外暴露资源集故用 wrapper；规则集仅评估器内部消费故内联）。
- 标准规则：error.rate>0.05(P0)/failover.exhausted.count>0(P0)/p95.latency.ms>3000(P1)/degradation.rate>0.2(P1)/token.usage.ratio>0.9(P2)，指标名对齐 AgentMetrics 派生快照。

**测试（542 GREEN）：** `AlertRuleEvaluatorTest` 4 例——超阈值触发+发通道、低于阈值不触发、指标缺失不误报、通道抛异常②降级不丢告警不抛。注入受控规则+录制/抛异常通道+固定时钟。

相关：[[phase15-t70-eval-controller-design]]、[[phase15-t66-route-weight-design]]、[[degradation-and-eval-principles]]。
