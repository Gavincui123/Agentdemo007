---
name: phase14-langgraph-design
description: Phase 14 LangGraph 集成——NoCloneStateSerializer/StepOutcome.Retry/per-step 审计收口/编排模式切换，8 验收全满足（477 测试）
metadata:
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-07T01:19:36.326Z
---

Phase 14 LangGraph4j 集成已落地（langgraph4j-core 1.5.14，groupId org.bsc.langgraph4j），套件 475→477 全绿，4 条验收功能性全满足（依赖兼容/多步条件分支/循环自纠正/线性-图输出一致）。

**④统一收口是本 Phase 主轴（呼应 [[phase8-context-design]] [[phase9-10-capability-tool-rag-design]]）：** 同一份 `PipelineStep` 经 `GraphNode` 适配为 langgraph4j 节点，节点入参/出参都走 `PipelineContext`+`StepOutcome`，换引擎不换收口。`PipelineExecutor` 接口是切换锚点——`PipelineOrchestrator`(线性) 与 `GraphExecutor`(图) 都实现之，`ChatController` 只依赖接口。

**NoCloneStateSerializer（核心决策，非显而易见）：** langgraph4j 默认 `ObjectStreamStateSerializer` 在节点间 Java-序列化 state——`PipelineContext` 非 Serializable 会炸；且序列化会断开共享引用，使节点对 context 的 mutate 无法传播到后续节点/终态。故自建 `NoCloneStateSerializer`（stash 引用 + 返回同一引用），保留共享可变传播。代价：牺牲 langgraph4j state snapshot/rollback——本项目自有审计（[[phase13-persistence-audit-design]]）+ 持久化收口，不需图框架回滚。非线程安全（stashed 字段）→ **每请求新建** `AgentStateGraph`+serializer 实例。

**OUTCOME_KEY 终态存活（经验验证，非显然）：** langgraph4j 最终返回的 state 确实持有末节点的 `OUTCOME_KEY`（NoCloneStateSerializer 共享引用语义使其成立）。GraphExecutor 短路/降级/异常三条终端路径都读它。**per-step 副作用必须在 GraphNode 内经 `StepOutcomeAuditor` 即时应用**（不能延迟到 GraphExecutor 末尾再读 OUTCOME_KEY——那样只能看到末次 outcome，先前 Degrade 信号丢失，degraded() 不累积，terminal 错）。`StepOutcomeAuditor` 是线性/图两模式复用的单一审计真相源。

**StepOutcome.Retry（第 4 个 sealed 变体）：** "重试本步"——图模式下 retry 边→本节点自循环再执行（工具自纠正，§5.13）；线性模式等价 Proceed（线性不图循环，重试在 step 内部自理）。`maxIterations` 超限→langgraph4j 抛 `CompletionException(IllegalStateException)`→GraphExecutor 捕获→INTERNAL 话术（死循环护栏兜底）。Retry 信号是强类型 sealed 变体而非 Map flag（§5.14 不外泄字段）。

**编排模式切换（@ConditionalOnProperty 互斥装配）：** `app.pipeline.mode=linear`(缺省，matchIfMissing=true)→`PipelineOrchestrator` @Component 装配；`app.pipeline.mode=graph`→`LangGraphConfig` @Bean 注册 `GraphExecutor`。两实现 guard 互斥，`@Autowired` 单个 `PipelineExecutor` 若两 bean 并存会抛 NoUniqueBeanDefinitionException（互斥的隐式保证）。属性走 `app.*` 命名约定（app.pipeline.mode / app.langgraph.max-iterations:25），非 agentdemo.*。`PipelineModeGraphTest`/`PipelineModeLinearTest` @SpringBootTest 切换断言。

**组件清单：** langgraph/ 包 `GraphNode`(CONTEXT_KEY/OUTCOME_KEY + per-step 审计 + 异常捕获 ShortCircuit(INTERNAL))、`GraphEdge`(四分支映射 proceed/degrade/shortCircuit/retry→self)、`AgentStateGraph`(每节点 conditional edges，末节点 next=END 仍走 conditional 以支持自循环)、`GraphExecutor`(终端收口镜像 orchestrator.terminal，3-参 maxIter)、`NoCloneStateSerializer`。config/`LangGraphConfig`、common/pipeline/`PipelineExecutor`、`StepOutcomeAuditor`(从 orchestrator 内联提取的共享收口)。

**坑：** 测试桩同名碰撞——langgraph4j `addNode` 按 name 去重，同 class simpleName 的桩（如多个 `AppendStep`）会 "node id already exist"→GraphStateException→INTERNAL。生产 step 是不同 @Component 类不撞；测试桩须传 name 参区分。surefire `-Dtest='com.agentdemo007.langgraph.*'` 包通配不匹配（按类名 regex），须显式列类名。

**DEFERRED：** 真实多步推理条件分支（当前图是线性 steps 链 + Retry 自循环，尚未引入按意图分叉的多出口条件图）；LangChain4j 流式契约接入后图节点的流式适配。
