# 高风险固定工作流（LangGraph）设计与实现计划

> 状态：方向已纠正（2026-09-12）。原"per-intent 真 DAG（每意图动态 DagSpec）"已否决并回滚：**RoutePlan
> 只是每请求结构化路径计划，本身无 DAG**。低风险意图靠现有固定 @Order 链 + #135 自跳过，无 DAG；
> DAG 落点 = 高风险操作时在 LangGraph 构建**固定**工作流子图。关联 [[routeplan-design]]、[[phase14-langgraph-design]]。
> 文件名历史遗留（可按需重命名 high-risk-workflow-dag.md）。

---

## 0. 目标与非目标

**目标**
- RoutePlan 产出的路径计划里，仅 `requires_workflow=true` 的高风险意图（refund_request / return_request）
  触发一个**固定** LangGraph 工作流子图来跑。
- 子图只跑**高风险尾**：提交退款/退货动作 + 审批门（等人工批准/驳回）。tool（get_order_detail）/RAG
  （after_sale_policy）**留在主链**（#135 已工作，不动）——不重复 tool/RAG 逻辑。
- 驳回→改写重提（图自循环 Retry，maxIterations 护栏）；批准→完成；超时→话术短路。
- 收口：工作流产出走 `PipelineContext` 强类型字段（`workflowResult` / 复用 `hitlTicketId`），下游不变。

**非目标**
- 不做"每意图动态 DAG"——低风险意图就靠现有固定链 + #135 自跳过，无 DAG。
- 真 token 流式 / LangSmith / 在途会话视图（另题）。
- 真 async 审批恢复（webhook 回调）——最小路径用同步 seam，迭代后补。

---

## 1. 现状真相

- `RoutePlan` 是**结果载体**（record，无决策逻辑），扁平访问器 delegate 到 candidate：
  `requiresWorkflow()` / `needsRag()` / `needsBusinessTools()` / `fallbackPolicy()` / `riskLevel()`。
  `isEmpty()` 含 `!requiresWorkflow()`。
- `RoutePlanBaselines` 仅 `refund_request` / `return_request` `requires_workflow=true`
  （HIGH, WORKFLOW_FIRST, tools=[get_order_detail], domains=after_sale/received_return_policy）。其余 intent 低风险无 workflow。
- 现有 P14 LangGraph（`GraphExecutor`/`AgentStateGraph`/`GraphNode`/`NoCloneStateSerializer`）把同一份
  PipelineStep 列表编译为**线性链**图（条件边只 PROCEED/Degrade→下一、ShortCircuit→END、Retry→自己），
  **没有**真分支子图。本次新增的是**独立固定子图**（2 节点 StateGraph），不复用 `AgentStateGraph`
  （那是顶层线性包装）。
- `HitlStep@610` = **执行前**转人工门（TRANSFER_TO_HUMAN→建工单 + `HitlDecision` 决议，未确认
  `ShortCircuit(HITL_TIMEOUT)`）。工作流审批门是**执行后**审批——语义不同，用独立 `WorkflowApprovalDecision`
  seam（决策 D：不复用 `HitlDecision`）。

---

## 2. 设计

### 2.1 触发：WorkflowExecutionStep（@670，紧随 Rag@660、先于 ContextBuilder@700）

新增 `PipelineStep` @670。读 RoutePlan：
- `rp==null || rp.source()!=LLM_WITH_POLICY_CONSTRAINTS || !rp.requiresWorkflow()` → **Proceed**
  （低风险 / noop / 兜底候选不采信 routePlan，回退旧链，同 #135）。
- 否则 → 调用固定工作流子图 → 写 outcome。
- 属性门控 `app.workflow.enabled`（默认 false）→ Proceed 空过，旧链完全不变（最小侵入，dev/noop 不破）。

### 2.2 固定子图：RefundWorkflowGraph（langgraph4j StateGraph，预定义不变）

节点（复用底层服务，非手撸）：

| 节点 | 复用 seam | 产出写 context |
|---|---|---|
| `submit_refund` | `RefundService.submit(context)` | `workflowResult`（退款请求 id/状态） |
| `approval_gate` | `WorkflowApprovalDecision.await(workflowResult)` | `Approved`/`Denied`/`Timeout` |

边（固定）：
- `START → submit_refund`
- `submit_refund → approval_gate`
- `approval_gate` 条件边：`Approved → END`；`Denied → submit_refund`（Retry 改写重提）；`Timeout → END`（终态短路）。
- `maxIterations` 护栏（防驳回死循环，镜像 P14 `setMaxIterations`）。

子图状态：复用 `NoCloneStateSerializer` + `AgentState`，携带 `PipelineContext`（同 `GraphNode.CONTEXT_KEY`
模式）。节点动作读 context（query/orderId）+ 写 context 强类型字段（业务状态永远在 PipelineContext，不散落 Map）。

### 2.3 seam（NO_OP 最小，迭代补真）

- `RefundService`：interface，`String submit(PipelineContext)` → 退款请求 id。NO_OP 返回 `"WF-STUB-..."`。
- `WorkflowApprovalDecision`：interface，sealed `Outcome permits Approved(approver)/Denied(reason)/Timeout()`。
  `Outcome await(String workflowResult)`。NO_OP 返回 `Approved("auto")`（最小路径假定批准，跑通图结构 + Retry 语义）。
- 真 seam（调退款后端 / 真人工审批 + async resume）迭代后补，不阻塞图结构。

### 2.4 降级 / 收口

- 工作流异常 → `ShortCircuit(INTERNAL)`（镜像 `GraphNode` per-step catch）。
- 审批 Timeout → `ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT)`（新增 scenario，语义区分于 HITL_TIMEOUT）。
- 工作流产出走 context 强类型字段；终态仍由顶层 `PipelineExecutor` terminal 收口（换子图不换出口，④）。

---

## 3. TDD 切片（铁律②：最小可跑通先，每片 RED→GREEN）

**Slice 0｜依赖面核实（铁律①）✅ 已完成**
- langgraph4j API（`addNode`/`addConditionalEdges`/`compile`/`invoke`/`AsyncNodeAction`/`NoCloneStateSerializer`）——读 P14 确认。
- `RoutePlan.requiresWorkflow()` / `RoutePlanBaselines` refund/return 字段——确认。
- `HitlDecision` sealed `Outcome` 模式——确认（`WorkflowApprovalDecision` 镜像）。

**Slice 1｜最小固定子图（submit→approval→approved）✅ 已完成 GREEN 1/1**
- `RefundWorkflowGraph`：2 节点 + 边。`RefundService`/`WorkflowApprovalDecision` NO_OP（lambda）。
- GREEN：invoke 子图 → submit 被调、approval 返回 Approved → END、`workflowResult` 写 context。
  （`RefundWorkflowGraphTest.invoke_submitThenApproval_approved_writesWorkflowResult`）

**Slice 2｜驳回→Retry 自循环 ✅ 已完成 GREEN 2/2**
- `WorkflowApprovalDecision` 返回 Denied → 子图条件边回 submit 改写重提 → 二次批准→END。
- GREEN：canary `AtomicInteger` 证 submit 跑 2 次、`workflowResult` 为最终提交 id（WF-2）。
  （`RefundWorkflowGraphTest.invoke_deniedThenApproved_submitRetriesUntilApproved`）

**Slice 3｜触发步骤 + source 门控 ✅ 已完成 GREEN 5/5**
- `WorkflowExecutionStep@670`（`@Component @Order(670) @ConditionalOnProperty(app.workflow.enabled=true)`）：
  high-risk LLM 候选→invoke 子图；低风险/兜底/null→Proceed。
- `WorkflowConfig`（`@Configuration @ConditionalOnProperty`）NO_OP seams（恒批准）+ `RefundWorkflowGraph`。
- GREEN：null/DETERMINISTIC_FALLBACK/requiresWorkflow=false 三 no-invoke 守卫 canary==0；
  requiresWorkflow=true canary==1 + `workflowResult` 写入；子图抛→`ShortCircuit(INTERNAL)`。
  线性等价：`app.workflow.enabled` 缺省=false→零工作流 bean→@670 槽空→旧链 660→700 不变。

**Slice 4｜Timeout 收口 ✅ 已完成 GREEN 9/9（真 seam 延后）**
- approval Timeout→`ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT)`（新增 scenario，决策 E ✅）。
  `RefundWorkflowGraph.invoke` 改返回 `Outcome`（从终态读 `APPROVAL_KEY`）；`WorkflowExecutionStep`
  按终态收口：Approved→Proceed、Timeout→`ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT)`、
  Denied 终态（maxIterations 强制终止）→`ShortCircuit(INTERNAL)`。
- 真 seam（`RefundService` 调退款后端 / `WorkflowApprovalDecision` 真人工审批 + async resume）
  **延后**：本仓为脚手架，无退款后端/审批系统可接；同步阻塞 seam 无外部 resolver 仍为桩（加并发复杂度无实益）。
  遵项目 NO_OP-seam 惯例（同 `HitlDecision`），真 impl 待外部系统就绪后接入。决策 F：sync 最小→NO_OP 保留。

**Slice 5｜回归 + 冒烟 + memory ✅（eval 拓扑延后）**
- 全量回归 ✅ 816 GREEN（0 失败 0 错误，4 smoke skipped）；Slice 3 814→Slice 4 816。
- 冒烟：noop ✅（`RoutePlanWiringIntegrationTest` `llm.enabled=false` 4/4，SiliconFlow 间歇 500 重跑通过=外部 flaky 非代码）；真 model 冒烟=4 skipped（env-gated，须 export SF_KEY+DS_KEY）。
- memory ✅：`[[routeplan-design]]` 缺口⑥（per-intent DAG）标注作废、⑦（Workflow 子图）标注已实现；新增 `[[high-risk-workflow-design]]` memory。
- **eval workflow 拓扑断言延后**：pipeline 级 eval 触发工作流须 `source=LLM_WITH_POLICY_CONSTRAINTS` 的 refund RoutePlan，noop 只产 deterministic fallback（source=FALLBACK→工作流守卫跳过）→ eval 必依赖真 route_model（`llm.enabled=true` 打 SiliconFlow，flaky）。拓扑结构改由 9 个单测证（submit→approval→END / Denied→Retry / Timeout→ShortCircuit / 全守卫，全覆盖）；pipeline 级 eval 待真 model 稳定后作 smoke-gated eval 补（同 4 skipped 冒烟模式）。

---

## 4. 验收

- [x] refund/return（`requires_workflow=true` + LLM 源）触发固定子图；其他意图不触发（旧链不变）。〔Slice 3 五测 + 回归〕
- [x] 子图：submit→approval 串行 ✅；Denied→Retry 自循环 ✅（Slice 1/2）。maxIterations 终止护栏
      镜像 P14 `setMaxIterations(10)`——Retry 边已证；全 Denied→强制终止→INTERNAL 待 Slice 5 显式测。
- [x] 工作流产出走 `PipelineContext` 强类型字段（`workflowResult`），`ContextBuilder`/`OutputStep` 零改动。〔回归证〕
- [x] `app.workflow.enabled=false` 全量回归不破（`@ConditionalOnProperty` 缺省不装配→零工作流 bean→旧链不变，同 #135）。〔回归 814〕
- [x] `WorkflowApprovalDecision` ≠ `HitlDecision`，审批门 ≠ `HitlStep@610` 转人工门，语义不混。〔决策 D〕
- [x] 审批 Timeout→`ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT)`，不假装未审批退款成功（submit 已跑、退款已提交但未获批准）。〔Slice 4〕
- [ ] eval workflow 拓扑断言全绿（**延后**：pipeline 级触发须真 route_model 产 LLM 源 refund RoutePlan，noop 只产 fallback→守卫跳过；拓扑由 9 单测证；待真 model 稳定作 smoke-gated eval 补）。

---

## 5. 开放决策

- **D. WorkflowApprovalDecision 独立 seam**（✅ 已选）——不复用 `HitlDecision`（执行后审批 vs 执行前转人工，语义不同）。
- **E. Timeout scenario ✅ 已选新增** `WORKFLOW_APPROVAL_TIMEOUT`（语义区分于 `HITL_TIMEOUT`：执行后审批超时 vs 执行前转人工超时）。
- **F. 真 async 审批恢复 ✅ 延后**：最小用 NO_OP seam（恒批准，跑通图结构 + 全终态含 Timeout）；真同步阻塞 / async webhook resume 待退款后端 + 审批系统就绪后接入（脚手架阶段无外部 resolver，加并发复杂度无实益）。

---

## 6. 风险与护栏

- **source 门控**：只采信 `LLM_WITH_POLICY_CONSTRAINTS`；`DETERMINISTIC_FALLBACK`/noop 回退旧链
  （route_model 不可用时不触发工作流，不破 noop/兜底测试）。
- **不手撸已提供能力**（铁律①）：图结构用 langgraph4j `StateGraph`（非手撸 topo）；submit 委托
  `RefundService`、审批委托 `WorkflowApprovalDecision` seam。
- **TDD 每切片 RED 先**。
- **统一收口**：子图不直接产 `PipelineResult`，写 context；终态由顶层 executor 收口（④）。
- **冒烟隔离**：真 model 须 export `SF_KEY`+`DS_KEY`；noop 用 `@SpringBootTest(properties="llm.enabled=false")`。
