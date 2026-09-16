---
name: phase13-persistence-audit-design
description: Phase 13 异步持久化+审计：ChatTurnFinalizer 取代 CompositeLlmCallbackHandler、集中式编排器审计、接入层直投、@CreatedDate Instant 坑、手动ack+DLQ、MessagePublisher seam
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-06T15:30:28.196Z
---

Phase 13（异步持久化 + 审计体系）已全部落地，448 测试全绿。核心设计决策（多为 dev plan 偏差或坑，非读码可推）：

**1. ChatTurnFinalizer 取代 dev plan 列的 CompositeLlmCallbackHandler。** 终端后置钩子（`ChatController.run` 在 `orchestrator.run` 之后调 `finalizer.finalizeTurn(context,result)`），独立 try-catch 分收口会话持久化（`safePersistHistory`）+ 审计刷出（`safeFlushAudit`）——一者失败不跳另一者（§5.11 审计不丢）、均不传播（§5.12）。比 callback handler 更贴第四原则（终端统一收口）。方法名 `finalizeTurn` 避开 `Object.finalize` 遮蔽。dev plan 任务清单已注记此偏差。

**2. 集中式编排器审计，不各步散落。** `PipelineOrchestrator`（StepOutcome 收口处）在 ShortCircuit/Degrade/异常三分支各调 `context.addAuditEvent(...)`——单一地点、无重复、覆盖全场景。场景→类型映射 `AuditEventType.from(DegradationScenario)` 放在 audit 包（单一真相源，编排器内核不持映射逻辑）。**关键：Degrade 路径用 `from(d.scenario())`，类型是场景特定（如 SESSION_DOWN），非通用 DEGRADATION**；INTERNAL→EXCEPTION；通用降级（BAD_REQUEST/RATE_LIMITED/UNKNOWN_INTENT）→DEGRADATION。detail 前缀区分："短路:"/"降级:"/"异常:" + step.name() + scenario/message。

**3. 接入层注入审计直投 AuditProducer（非 context.addAuditEvent）。** `InputSecurityFilter` 在流水线之前短路，无 PipelineContext，故命中注入时直接 `auditProducer.publish(AuditEvent.of(INJECTION, TraceId.current(), null, "注入命中:"+mask(matched)))`——traceId 取 MDC（前置 TraceFilter @Order HIGHEST_PRECEDENCE 已写），sessionId=null（接入层未解析会话）。外层 try-catch（§5.12：MQ 故障不破坏短路 200 响应）。

**4. @CreatedDate 必须用 Instant，不能用 OffsetDateTime。** Spring Data 审计 `DefaultAuditableBeanWrapperFactory` 仅支持 LocalDateTime/LocalDate/LocalTime/Instant/Date/Long 作为目标类型；默认 DateTimeProvider 返回 LocalDateTime，转 OffsetDateTime 抛 `IllegalArgumentException: Cannot convert unsupported date type java.time.LocalDateTime to java.time.OffsetDateTime`。落库时间戳 `createdAt` 用 Instant（时区无关绝对时刻，正适合 DB 持久化时刻）；事件逻辑 `timestamp` 仍 OffsetDateTime（载荷显式携带，不经审计）。`PersistenceConfig`（@EnableJpaAuditing）为持久化模块配置锚点，镜像 RabbitMqConfig/RedisConfig。[[spring-boot-4-jackson3-gotchas]] 同源 Boot4 坑。

**5. 消费者手动 ack + DLQ + 测试不连 broker。** `HistoryPersistConsumer`/`AuditConsumer` 用 `@RabbitListener` + 手动 ack：`channel.basicAck(tag,false)` 成功 / `basicNack(tag,false,false)` requeue=false 死信到 `agent.dlq`。`@Component @ConditionalOnProperty(app.rabbitmq.enabled=true)`。RabbitMqConfigTest 加 `spring.rabbitmq.listener.simple.auto-startup=false` 阻止 listener 容器 .start()→连 broker（broker 不在时上下文起不来）。**坑：测试方法调 `verify(channel).basicNack(...)` 须声明 `throws IOException`（受检异常）**。

**6. MessagePublisher seam + CapturingMessagePublisher 假。** 生产者依赖 seam 非 RabbitTemplate → 单测用 `CapturingMessagePublisher`（published()/publishCount()/lastRoutingKey()/lastPayload()）替换 seam，无需 broker。NoopMessagePublisher（`enabled=false`/缺省，`matchIfMissing=true`）默认装配、只记日志；RabbitMqMessagePublisher（`enabled=true` 经 RabbitMqConfig @Bean 装配）吞 AmqpException 不抛。两者皆 §5.12"能跑通>完美"。

**7. 测试类同名坑。** `com.agentdemo007.web.InputSecurityFilterTest`（Phase 1 RestTemplate e2e 验收）已存在；surefire `-Dtest='InputSecurityFilterTest'` 按 simple name 匹配会同时命中两个包。审计单测改放 `com.agentdemo007.access.InputSecurityFilterAuditTest` 避免歧义。@Autowired 字段注入的 filter 单测用 `ReflectionTestUtils.setField` 设 objectMapper/phraseCenter/auditProducer + MDC.put(TraceId.MDC_KEY) 定 traceId + MockFilterChain（getRequest() 验短路未放行）。

**8. .bak 隔离编译阻断。** Maven 一起编译全部测试源；某测试引用未建类会阻断全量编译，无法跑目标 RED→GREEN。把阻断测试 `.java`→`.java.bak` 移出编译，跑完再还原。

收口贯穿：PipelineContext.auditEvents() 强类型 List<AuditEvent>（非 Map，§5.14），ChatTurnEvent/AuditEvent record→实体字段一一映射。与 [[degradation-and-eval-principles]] 四原则、[[phase11-12-hitl-output-design]] 终端/边界同形一脉。
