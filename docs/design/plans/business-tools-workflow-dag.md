# 业务工具 + 高风险工作流真 DAG 设计与实现计划

> 状态：设计已与用户对齐（2026-09-12）。扩展/取代 [[per-intent-dag]] 的 2 节点退款子图为 5+ 节点真 DAG，
> 新增 7 个 mock 业务工具（5 低风险 @Tool + 2 高风险走 Workflow），4 组**真 SiliconFlow** 测试。
> 关联 [[high-risk-workflow-design]]、[[routeplan-design]]、[[phase8-context-design]]、[[degradation-and-eval-principles]]、
> [[dont-hardwrite-use-dep-methods]]、[[langchain4j-boot4-compat-findings]]。

---

## 0. 目标与非目标

**目标**
- 8 个 mock 工具（mock 数据，后期补真 API/RAG）：
  - 低风险 @Tool：模拟查询订单 `OrderQueryTool`、查询用户信息 `UserQueryTool`、查询商品 `ProductQueryTool`、
    调用 RAG 查询退货政策 `ReturnPolicyTool`、调用 RAG 查询退款政策 `RefundPolicyTool`、
    调用 RAG 查询活动政策 `PromotionPolicyTool`（T3"活动政策"缺口补齐，mock 数据，RAG 通道带 citation）。
  - 高风险走 Workflow：退货 `ReturnService`、退款 `RefundService`（已有 seam，扩为提交动作）。
- **将 DAG 确实落到 Workflow**：把现有 2 节点 `RefundWorkflowGraph`（submit→approval）扩为 5+ 节点固定 DAG：
  `query_user → query_order → query_policy → validate →(条件边) pass: submit_approval → approval_gate → END；
  fail: → END(Rejected(reason))`。return/refund 共用一个参数化图 `AfterSaleWorkflowGraph`。
- **3 通道路由**（用户钦定）：
  - 外部系统工具（订单/用户/商品）→ `RunTime_*`（高置信数据，进 System 锚点层 Runtime 块）；
  - RAG 工具（退货/退款政策）→ `RAG_Messages`（带 citation，进 RAG 段 `ragFragments`+`ragCitations`）；
  - 简单计算工具（既有 TriangleArea/CircleArea/Multiplication）→ `Tool_message`（进 `toolResults`）。
- **4 组真 SiliconFlow 测试**（env-gated smoke，缺 SF_KEY+DS_KEY→skip）：
  1. 调用工具查询实时事实（订单/用户/商品）；
  2. 调用工具查询 RAG 政策（退货/退款）；
  3. 商品推荐场景：真模型一轮回放多 tool_calls（商品+用户+活动政策）→ 客服话术整合推荐；
  4. 退货/退款走 Workflow：验 5 节点 DAG 落地 + 校验分支（pass→提交审批 / fail→按情况告知客户）。

**非目标**
- 真 API / 真 RAG 后端接入（mock 先行，后期补；[[routeplan-design]] 缺口⑧）。
- 真 async 审批恢复（webhook）——同步 seam 最小，迭代后补（同 [[high-risk-workflow-design]] 决策 F）。
- AiServices 自驱动 agent 循环替手撸——工具调用仍走 `ToolCallExecutor` 单次前向（[[langchain4j-boot4-compat-findings]] A/B 叉口已定）。
- 每意图动态 DAG（已作废，[[routeplan-design]] 缺口⑥）。

---

## 1. 现状真相（已对码核实 2026-09-12）

- **PipelineContext 消费者字段**：`ragFragments: List<String>` + `ragCitations: List<String>`（Phase 20 citation，并行）、
  `toolResults: List<String>`、`workflowResult: String`。**无 tool-sourced 高置信 Runtime 通道**（缺口）。
- **ContextMerger 三层**（`ContextMerger.merge`）：`SystemAnchorLayer`(Sys→Runtime：summary+intent+time，折叠单条 System)
  → `ObjectiveDataLayer`(His→RAG→Tool：RAG 框为带【参考资料】隔离头的 User，Tool 为标准 ToolResult)
  → `UserInstructionLayer`(User)。
- **ToolCallExecutor.execute(query) → `List<String>`**（ToolExecutionStep L68）：单次前向，模型出 tool_calls 则逐个
  `ResilientToolExecutor`(包 `DefaultToolExecutor`) 执行，结果收集为 `List<String>`，交 `ToolExecutionStep` 全量塞
  `toolResults`。**不区分通道**（缺口）。
- **ToolSchemaProvider**：`ToolBinding(spec, bean, method)` 按 `@Tool` 方法名建 `bindingsByName`；`allSchemas()` /
  `schemasFor(names)` / `executors(breaker)`。**无 category 维度**（缺口）。
- **ToolExecutionStep @650**：#135 source 门控（`needsBusinessTools=false` 跳过）；熔断→`ShortCircuit(TOOL_FAILURE)`。
- **RefundWorkflowGraph**（2 节点）：`submit_refund`(RefundService.submit→workflowResult) → `approval_gate`
  (WorkflowApprovalDecision.await→APPROVAL_KEY)；条件边 Approved→END / Denied→submit(Retry) / Timeout→END。
  返回 `WorkflowApprovalDecision.Outcome`。
- **WorkflowExecutionStep @670**：`rp==null || source!=LLM_WITH_POLICY_CONSTRAINTS || !requiresWorkflow`→Proceed；
  否则 invoke 子图，按终态 `Approved→Proceed / Timeout→ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT) /
  Denied→ShortCircuit(INTERNAL) / 异常→ShortCircuit(INTERNAL)`。`app.workflow.enabled` 缺省 false 不装配。
- **OutputStep @800**：`flatten(assembledPrompt)→llmService.chatRaw→StructuredOutputGateway→securityFilter→setFinalReply`。
  **总调 LLM，无 presetReply 短路守卫**（缺口——E1 需补）。`Degrade(OUTPUT_FALLBACK)` / `ShortCircuit(MODEL_DOWN/FAILOVER_EXHAUSTED)`。
- **DegradationScenario** 枚举（系统故障话术）：INJECTION/BAD_REQUEST/.../MODEL_DOWN/FAILOVER_EXHAUSTED/TOOL_FAILURE/
  RAG_SKIP/HITL_TIMEOUT/WORKFLOW_APPROVAL_TIMEOUT/OUTPUT_FALLBACK/INTERNAL。业务驳回**不混入**此枚举（E1 决策）。

---

## 2. 设计

### 2.1 底层 typed mock 服务（单一真相源，Slice 1）

每个外部系统/RAG 能力一个 typed 服务（返回强类型 record），@Tool 包装与 DAG 节点**共用同一份 mock 数据**：

| 服务 | record | mock 数据 |
|---|---|---|
| `OrderQueryService` | `OrderRecord(orderId, userId, orderTime, status, items, amount)` | 几条 canned 订单（含本人/非本人/不存在/超 7 天） |
| `UserQueryService` | `UserRecord(userId, name, tier)` | canned 用户 |
| `ProductQueryService` | `ProductRecord(sku, name, price, stock, tags)` | canned 商品（含活动/库存） |
| `PolicyQueryService`（**单入口 seam**，政策类） | `PolicyFragment(text, source)` | 按 `PolicyDomain` 返 canned 政策文本 + citation（RETURN/REFUND/PROMOTION） |
| `ReturnService` / `RefundService`（已有 seam，扩提交动作） | `String submit(PipelineContext)` | 返回 "WF-RETURN-xxx" / "WF-REFUND-xxx" |

DAG 节点直接调 typed 服务（强类型校验）；@Tool 包装调同一服务、返回 String（LLM 消费用）。

**`PolicyQueryService` 单入口 seam（前期，后期接真 RAG）**：
- `interface PolicyQueryService { PolicyFragment query(PolicyDomain domain); }` + `enum PolicyDomain { RETURN, REFUND, PROMOTION }`。
- mock 实现 `MockPolicyQueryService`：按 domain 返 canned `PolicyFragment(text, source)`（citation 标"退货/退款/活动政策知识库§x"）。
- 3 个政策 @Tool（`ReturnPolicyTool`/`RefundPolicyTool`/`PromotionPolicyTool`）+ DAG `query_policy` 节点**全部委托此单 seam**（各传自身 domain）。
- **后期单点切真 RAG**：加 `RagPolicyQueryService implements PolicyQueryService`，`query(domain)` 映射 domain→RoutePlan 知识域（RETURN→received_return_policy / REFUND→after_sale_policy / PROMOTION→promotion_and_member_policy）委托既有 `HybridRetriever`（Phase 20/21）。换实现不换 seam/调用方/工具 schema——单入口改造点。

### 2.2 @Tool 包装 + 3 通道路由（Slice 2）

**`@ToolChannel` 注解 + `ToolCategory` 枚举**（`capability/tool/`）：
- `enum ToolCategory { RUNTIME, RAG, COMPUTE }`。
- `@ToolChannel(ToolCategory)` 标在 `@Tool` 方法上（RUNTIME=外部系统高置信、RAG=知识库政策、COMPUTE=简单计算）。
- `ToolSchemaProvider.ToolBinding` += `ToolCategory category`（注解缺失默认 `COMPUTE`，向后兼容既有计算工具）；
  暴露 `categoryOf(name)` / `categoryMap()`。

**RAG 工具返回 JSON**（用户钦定"能标识知识库数据的 JSON 对象"，带 citation）：
- `ReturnPolicyTool` / `RefundPolicyTool` / `PromotionPolicyTool` 各委托 `PolicyQueryService.query(domain)`，
  返回 `{"text":"...","source":"退货政策知识库§3"}`（`PolicyFragment.toJson()`）。3 工具给 LLM 3 个独立 schema（模型按需选）。
- 既有计算工具返回纯文本 String（不变）。

**`ToolCallExecutor.execute` 返回结构化 `List<ToolCallResult>`**（record：`name, content, category`）：
- 执行每个 tool_call 时按 `categoryMap` 标 category。
- 契约变更：原 `List<String>` → `List<ToolCallResult>`；`ToolCallExecutorTest`（ScriptedModelExecutor 单测）同步更新。

**`ToolExecutionStep` 按 category 路由**（替现状全量塞 toolResults）：
- `RUNTIME` → `context.runtimeFacts().add(content)`（新字段）；
- `RAG` → 解析 JSON → `context.ragFragments().add(text)` + `context.ragCitations().add(source)`；解析失败兜底
  raw 入 ragFragments、citation 空（②每步降级，不阻塞）；
- `COMPUTE` → `context.toolResults().add(content)`（现状不变）。

**`SystemAnchorLayer` 扩 Runtime 块纳入 `runtimeFacts`**（高置信外部系统事实与 summary/intent/time 同居
System 锚点层；非空逐条拼接，空跳过）。**不**降级进 RAG/Tool 段（用户钦定 RunTime_* = 高置信独立通道）。

**新 PipelineContext 字段**：`runtimeFacts: List<String>`（additive；SystemAnchorLayer 消费，空跳过）。

### 2.3 高风险固定 DAG（Slice 3，将 DAG 确实落到 Workflow）

**一个参数化图 `AfterSaleWorkflowGraph`**（return/refund 共用，D1 决策）：
构造参数：`policyQueryService`（`PolicyQueryService` 单 seam，复用）+ `policyDomain`（RETURN/REFUND，区分
return/refund 流）+ `validationRule`（Return/RefundValidationRule）+ `submitService`（Return/RefundService）
+ `approvalDecision`（WorkflowApprovalDecision，复用）。

节点（固定）：
```
START → query_user → query_order → query_policy → validate
                                                        │ 条件边
                                          ┌─────────────┴──────────────┐
                                          ▼ pass                        ▼ fail
                                  submit_approval              END → Rejected(reason, message)
                                          │                     （按情况告知客户，不提交）
                                  approval_gate
                                          │ Approved→END / Denied→submit(Retry) / Timeout→END
```

- `query_user` / `query_order`：调 typed mock 服务（§2.1），结果写图状态（供 validate 读）。
- `query_policy`：调 `policyQueryService.query(policyDomain)`（单 seam，复用 @Tool 同一政策源），结果写图状态。
- `validate`（纯逻辑）：校验订单归属本人 / 订单存在 / 7 天无理由窗口（return）或退款窗口（refund）。
  pass→`submit_approval`；fail→写 `Rejected(reason)` 终态、条件边到 END。
- `submit_approval`：`submitService.submit(ctx)`→`workflowResult`（复用现有 seam）。
- `approval_gate`：`approvalDecision.await(workflowResult)`→复用 `WorkflowApprovalDecision.Outcome`
  (Approved/Denied/Timeout)；条件边 Approved→END / Denied→submit(Retry，maxIterations 护栏) / Timeout→END。

**终态类型 `AfterSaleWorkflowOutcome`**（sealed，扩出现有 3 态）：
`Approved` / `Rejected(Reason reason, String customerMessage)` / `Denied(String reason)`（maxIterations 终态）/
`Timeout`。`invoke(PipelineContext)` 返回此类型（从终态读）。`Reason` 枚举：
`ORDER_NOT_FOUND` / `ORDER_NOT_OWNED` / `BEYOND_7_DAY`（return）/ `REFUND_WINDOW_EXPIRED`（refund）。
替代 `RefundWorkflowGraph`（2→5+ 节点真 DAG）。

### 2.4 收口（Slice 4，E1 决策：BusinessRejection 终态，非 DegradationScenario）

**`WorkflowExecutionStep` 按终态收口**（替现状 3 态 instanceof 链）：
- `Approved` → `Proceed`；
- `Rejected(reason, message)` → **`context.setPresetReply(message)` + `Proceed`**（业务规则驳回≠系统失败，
  不 ShortCircuit；话术走 presetReply 短路，复用话术短路机制但不污染 DegradationScenario）；
- `Timeout` → `ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT)`（现有）；
- `Denied`（maxIterations 终态）→ `ShortCircuit(INTERNAL)`（现有）；
- 异常 → `ShortCircuit(INTERNAL)`（现有）。

**`OutputStep @800` 加 presetReply 守卫**（@670 在 @800 之前，presetReply 已就位）：
`process` 开头 `if (context.presetReply() != null) → context.setFinalReply(securityFilter.filter(presetReply)); return Proceed;`
（跳过 `llmService.chatRaw` + StructuredOutputGateway，零 LLM 话术短路）。
新 `PipelineContext.presetReply: String` 字段（additive；非业务驳回为 null，OutputStep 走原 LLM 链不变）。

**统一收口（④）**：图不直接产 `PipelineResult`，写 context（workflowResult/presetReply）；终态由顶层
`PipelineExecutor` 收口（换子图不换出口）。

### 2.5 4 组真 SiliconFlow 测试（Slice 5，env-gated smoke）

**纠正已收到**：Tests 1-4 **不用** ScriptedModelExecutor，全走真 SiliconFlow function-calling——否则验不出
工具 schema/描述是否真能被模型调用（用户原话"否则工具调用就失去真正意义"）。复用现有 env-gated smoke 模式
（缺 SF_KEY+DS_KEY→skipped，有→真打；Slice 5 RED 阶段定位既有 smoke 标注/标记）。

- **T1 实时事实**：真模型调 `OrderQueryTool`/`UserQueryTool`/`ProductQueryTool` → `runtimeFacts` 非空、含订单/用户/商品字段。
- **T2 RAG 政策**：真模型调 `ReturnPolicyTool`/`RefundPolicyTool` → `ragFragments`+`ragCitations` 非空、citation 含来源。
- **T3 商品推荐**（并行=模型一轮回放多 tool_calls，P1 决策顺序执行）：真模型对"推荐商品"emit 多 tool_calls
  （商品+用户+活动政策）→ 三通道各有结果 → 回复含客服话术整合推荐。
- **T4 退货/退款走 Workflow**：refund/return intent → `AfterSaleWorkflowGraph` 跑 → 验 5 节点 DAG 落地 + 校验分支：
  pass（本人+7 天内+订单存在）→ submit+approval→Approved；fail→按情况告知（订单非本人→ORDER_NOT_OWNED 话术 /
  超 7 天→BEYOND_7_DAY 话术 / 订单不存在→ORDER_NOT_FOUND 话术）→ presetReply 短路。

**既有 ScriptedModelExecutor 单测保留**（Slice 2）作 schema/路由 dispatch 逻辑回归（快、确定），**不替代**真 smoke。
路由/schema 逻辑用 scripted 单测（非"工具调用"语义，是路由判定）；4 组用户场景用真 SiliconFlow（"工具调用"语义）。

---

## 3. TDD 切片（铁律②：最小可跑通先，每片 RED→GREEN）

**Slice 0｜依赖面核实（铁律①）✅ 已完成**
- langgraph4j `StateGraph`/`addNode`/`addConditionalEdges`/`compile`/`invoke`/`NoCloneStateSerializer`——读 `RefundWorkflowGraph` 确认。
- LC4j `@Tool`/`@P` + `ToolSpecifications.toolSpecificationFrom` + `DefaultToolExecutor`——读 `ToolSchemaProvider` 确认。
- `ToolCallExecutor.execute` 返回 `List<String>`、`ToolExecutionStep` 全量塞 toolResults——对码确认（缺口）。
- `OutputStep @800` 总调 LLM 无 presetReply 守卫、`DegradationScenario` 枚举——对码确认（缺口）。
- ContextMerger 三层 + `ragFragments`/`ragCitations`/`toolResults` 消费者字段——对码确认。
- env-gated smoke 标注——Slice 5 RED 阶段定位（memory 确认 4 skipped 模式存在）。

**Slice 1｜底层 typed mock 服务（纯数据，无 LLM/无 Spring）✅ GREEN 20/20（2026-09-12）**
- `OrderRecord`/`UserRecord`/`ProductRecord` + `OrderQueryService`/`UserQueryService`/`ProductQueryService`
  （canned mock 数据覆盖本人/非本人/不存在/超 7 天）。
- `PolicyFragment(text,source)` + `enum PolicyDomain{RETURN,REFUND,PROMOTION}` + `PolicyQueryService` 单入口 seam
  + `MockPolicyQueryService`（按 domain 返 canned 政策文本+citation，§2.1 决策 R 前期）。
- `RefundService` 已是 `String submit(PipelineContext)` seam（无需扩）；`ReturnService` 延后 Slice 3（DAG 提交节点用）。
- RED→GREEN：`MockPolicyQueryServiceTest`5 + `OrderQueryServiceTest`5 + `UserQueryServiceTest`5 + `ProductQueryServiceTest`5
  = 20/20 GREEN（compile-RED 由类型不存在→GREEN 反向印证；@Component plain class + Optional + null 幂等 + record carriers，镜像 HumanTicketService 约定）。

**Slice 2｜@Tool 包装 + @ToolChannel + 3 通道路由（单测，ScriptedModelExecutor）✅ GREEN 12 新测 + 4 既有契约同步（2026-09-12，全量 848/0/4 skipped）**
- `ToolCategory` 枚举 + `@ToolChannel` 注解；`ToolBinding`+=category；`ToolSchemaProvider.categoryOf/categoryMap`。
- `OrderQueryTool`/`UserQueryTool`/`ProductQueryTool`（@ToolChannel(RUNTIME)，调服务返 String）；
  `ReturnPolicyTool`/`RefundPolicyTool`/`PromotionPolicyTool`（@ToolChannel(RAG)，委托 `PolicyQueryService.query(domain)` 返
  `PolicyFragment.toJson()`）；既有计算工具默认 COMPUTE。6 业务 @Tool 注册进 `ToolConfig.toolSchemaProvider`（10 工具单源）。
- `ToolCallExecutor.execute` → `List<ToolCallResult(name,content,category)>`（契约变更：6-arg 构造加 categoryMap）。
- `ToolExecutionStep` 按 category 路由（RUNTIME→runtimeFacts / RAG→`PolicyFragment.fromJson` 拆 ragFragments+ragCitations / COMPUTE→toolResults）；
  畸形 JSON 兜底 raw 入 ragFragments、citation 空（②每步降级）。
- `PipelineContext.runtimeFacts` 字段；`SystemAnchorLayer` 纳入 runtimeFacts（Runtime 块高置信事实）。
- `PolicyFragment.toJson/fromJson`（Jackson 3 readTree→treeToValue，镜像 RouteCandidateParser 范式，readValue(String) v3 已移除）。
- 契约同步波及面（4 既有测试）：`ToolCallExecutorTest`/`ToolExecutionStepCircuitTest`/`ToolExecutionStepMetricSafetyTest`
  （5-arg→6-arg + List<String>→List<ToolCallResult> override）/`ToolExecutionStepTest`（Mockito thenReturn List<ToolCallResult>）/
  `ToolCircuitWiringTest`（4→10 工具装配断言）。全量 848 GREEN（4 smoke skipped）。
- RED→GREEN：`ToolSchemaProviderCategoryTest`6（@ToolChannel 反射+categoryOf/categoryMap，用真 @Tool 包装）+
  `ToolExecutionStepRoutingTest`6（scripted 罐装 ToolCallResult → 3 通道路由 + 畸形 JSON 兜底）= 12 新测。

**Slice 3｜AfterSaleWorkflowGraph 6 节点 DAG（单测，typed 服务，无 LLM）✅ GREEN 8/8（2026-09-12，全量 856/0/4 skipped）**
- `AfterSaleWorkflowGraph`（参数化 8-arg：userService + orderService + policyService + policyDomain + validationRule
  + submitService + approvalDecision + currentUserId）。固定 6 节点 + 2 条件边：query_user→query_order→query_policy
  →validate→(pass: submit_approval→approval_gate→END / fail: END→Rejected) + approval 条件边(Approved→END / Denied→Retry / Timeout→END)。
  状态键 CONTEXT/ORDER/USER/POLICY/FAIL/APPROVAL/OUTCOME；Optional 包裹可空召回（Map.of 不容 null）；
  `extractOrderId` 正则从 rawInput 提取（mock 期，真接入后 RoutePlan required_entity 注入）；`rejectionMessage(Reason)` 按情况话术。
- `AfterSaleWorkflowOutcome` sealed（Approved/Rejected(reason,message)/Denied(reason)/Timeout）+ `Reason` 枚举
  （ORDER_NOT_FOUND/ORDER_NOT_OWNED/BEYOND_7_DAY/REFUND_WINDOW_EXPIRED）。
- `AfterSaleValidationRule` seam + `ReturnValidationRule`（7 天窗口默认，可注入）/ `RefundValidationRule`（30 天窗口默认）；
  注入 Clock 保测试确定性（固定 2026-09-12 钉窗口边界），校验顺序 存在→归属→窗口 短路。
- `AfterSaleSubmitService`（@FunctionalInterface seam，复用 RefundService 同形 `String submit(ctx)`）。
- RED→GREEN 8 测：pass(ORD-001→Approved+submit×1) / fail_orderNotOwned(ORD-003→ORDER_NOT_OWNED+不提交) /
  fail_beyond7Day(ORD-002→BEYOND_7_DAY) / fail_orderNotFound(ORD-999→ORDER_NOT_FOUND) / approvalTimeout→Timeout /
  approvalDeniedThenApproved_retriesSubmitThenEnds(Denied→Retry→submit×2→Approved) / refundFlow_ownedWithinWindow_approved /
  refundFlow_beyondWindow_rejectedRefundWindowExpired(2 天窗口)。
- **`MAX_ITERATIONS=20` 修正**：原镜像 RefundWorkflowGraph 取 10，但本图 6 节点+4 前缀比 2 节点图大——langgraph4j
  迭代预算按 generator yield 计（含节点执行+条件边/回边/END 终止开销，非纯节点数），实测 1 次 deny→retry→approve 即
  耗 ~10 yield（4 前缀 + submit/approval×2 + 回边/END 开销），10 在 retry 边界 trip。诊断证 ctx 仍共享（NoClone 正常、
  approval#2 读到 fresh workflowResult 非陈旧）、approval#2 确批准——纯预算不足。放大到 20（允 ~5 次 deny-retry 后仍
  强制终止→收口 INTERNAL，保留防死循环原义）。**memory 钉死 langgraph4j maxIterations 按 generator yield 计非节点数。**

**Slice 4｜WorkflowExecutionStep 收口 + OutputStep presetReply 守卫✅ GREEN 18/18（2026-09-12，全量 861/0/4 skipped）**
- `PipelineContext.presetReply` 字段（additive；非业务驳回为 null，走原 LLM 链不变）。
- `AfterSaleWorkflow` seam（@FunctionalInterface，`invoke(ctx)→AfterSaleWorkflowOutcome`）；`AfterSaleWorkflowGraph implements` 此 seam
  （零逻辑改，方法签名天然匹配）；WorkflowExecutionStep 依赖 seam 非具体图类——单测 scripted lambda 聚焦收口逻辑（图内部 DAG 由 Slice 3 测覆盖）。
- `WorkflowExecutionStep` 收口重写：两 `@Qualifier` seam（refund/return）按 `rp.intent()` 选图（"return_request"→退货图，否则→退款图，
  RoutePlanBaselines 钉两 intent 真实存在）；按 `AfterSaleWorkflowOutcome` 四态映射——Approved→Proceed /
  Rejected(reason,message)→`setPresetReply(message)`+Proceed（业务驳回≠系统失败，E1：不 ShortCircuit/不混 DegradationScenario）/
  Timeout→ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT) / Denied→ShortCircuit(INTERNAL) / 异常→ShortCircuit(INTERNAL)。
  退役旧 RefundWorkflowGraph 注入（构造 1-arg→2-arg seam）；旧 RefundWorkflowGraph/RefundService @Bean 从 WorkflowConfig 剥离
  （类 + RefundWorkflowGraphTest 保留作已验证前身，plain 单测不依赖 Spring 仍 GREEN）。
- `OutputStep` process 开头 presetReply 守卫：见非空即 `securityFilter.filter(presetReply)`→setFinalReply→Proceed，跳 chatRaw/网关/Schema 重试（零 LLM 话术短路）；不标记 degraded。
- `WorkflowConfig` 重写：`refundAfterSaleWorkflow`/`returnAfterSaleWorkflow` 两 @Bean（AfterSaleWorkflowGraph 参数化：REFUND+RefundValidationRule / RETURN+ReturnValidationRule，
  Clock.systemUTC()、currentUserId 固定 U100[已知限制·真 auth per-request 迭代后补]、共享 AfterSaleSubmitService NO_OP + WorkflowApprovalDecision NO_OP）。
- RED→GREEN：`WorkflowExecutionStepTest`10（3 守卫 skip + 5 收口 outcome + 2 return/refund 选图，scripted seam）+ `OutputStepTest`+1（presetReply 守卫跳 LLM、finalReply=话术、modelResponse 不写、非降级）。
  **坑**：bash `cd src` 后 cwd 持久化→`./mvnw` "No such file" 静默失败（grep 无"No such"模式误判空输出=classifier drop），须 `cd 仓库根` 重跑。

**Slice 5｜4 组真 SiliconFlow smoke（env-gated）✅ GREEN 4/4 skipped（2026-09-12，全量 865/0/8 skipped）**
- 定位既有 smoke 标注：`OpenAiChatModelSiliconFlowSmokeTest`（SF_KEY+Assumptions.assumeTrue 同模式）。
- 新 `BusinessToolsSiliconFlowSmokeTest`（4 测，全 env-gated SF_KEY，无 key→skipped 不报错）：
  - **T1 实时事实**：真 SF + 6 业务 @Tool 单源 → 真模型调订单/用户/商品工具 → 真 `DefaultToolExecutor` 执行 →
    `ToolCallResult` category=RUNTIME + content 非空（断言结构性：tool_calls 真发出 + 通道正确，真模型非确定不断精确串）。
  - **T2 RAG 政策**：真模型调退货/退款政策工具 → content 为 `PolicyFragment` JSON → `PolicyFragment.fromJson` 解析 →
    text+source 非空（citation）+ category=RAG。
  - **T3 商品推荐**（P1 顺序执行=模型一轮回放多 tool_calls）：多工具需求 prompt（商品+活动政策）→ `results.size()≥2` +
    通道跨 RUNTIME+RAG。
  - **T4 退货/退款走 Workflow**（用户选定"加真 SF 路由 RoutePlan"——图内无 LLM，real SF 落在路由）：
    `RoutePromptBuilder.build(null,"我要退款 ORD-003")` → 真 SF `.chat(prompt)`（关思考、无 tools、纯文本 JSON）→
    `RouteCandidateParser.create().parse(raw)` → 断言 candidate 可解析 + `requiresWorkflow=true` + riskLevel=HIGH +
    fallbackPolicy=WORKFLOW_FIRST + intent="refund_request"；再确定性图收口（`AfterSaleWorkflowGraph` refund + ORD-003≠U100→
    `Rejected(ORDER_NOT_OWNED)`）验真 SF 路由结果落到 Slice 4 收口。
- 真模型 `.doChat(ChatRequest{tools})` 镜像 `ToolCallExecutor.execute`（直用真 `OpenAiChatModel` 不经网关，冒烟聚焦
  "真模型能否调工具"语义，路由/容灾逻辑由既有单测覆盖）；`enable_thinking=false`（决策调用铁律）。
- RED→GREEN：无 SF_KEY 时 4/4 skipped（env-gated GREEN）；有 SF_KEY 时真打（用户 export SF_KEY+SF_MODEL 后跑）。

**Slice 6｜回归 + memory**
- 全量回归 GREEN；memory 更新（[[high-risk-workflow-design]] 扩 5 节点 DAG、[[routeplan-design]] 缺口⑧真业务工具接入标记进行中）。

---

## 4. 验收

- [x] 8 个 mock 工具就绪：6 低风险 @Tool（含 `PromotionPolicyTool`）注册到 `ToolConfig.toolSchemaProvider`；2 高风险走 Workflow（不上 @Tool schema——return/refund 共用 `AfterSaleSubmitService` seam + `AfterSaleWorkflowGraph` 参数化图，`RefundService`/`ReturnService` 同形 seam 退役保留）。
- [x] 3 通道路由：RUNTIME→runtimeFacts（System Runtime 块）/ RAG→ragFragments+ragCitations（带 citation）/ COMPUTE→toolResults。
- [x] 6 节点 DAG 落地：query_user→query_order→query_policy→validate→(pass:submit_approval→approval_gate / fail:END→Rejected)。
- [x] 校验失败按情况告知：ORDER_NOT_OWNED / BEYOND_7_DAY(或 REFUND_WINDOW_EXPIRED) / ORDER_NOT_FOUND → presetReply 话术短路。
- [x] BusinessRejection ≠ DegradationScenario（业务驳回≠系统失败，语义不混，E1）。
- [x] `app.workflow.enabled=false` 全量回归不破（@ConditionalOnProperty 缺省不装配→旧链不变，865/0/8）。
- [x] 4 组真 SiliconFlow smoke：有 SF_KEY 时真打（验真模型能调工具/路由）；无 key skipped（4/4 env-gated）。
- [x] 统一收口：图写 context（workflowResult/presetReply），终态由顶层 executor 收口（④）。

---

## 5. 开放决策（已与用户对齐 2026-09-12）

- **E（校验失败收口）= E1 新 BusinessRejection 终态**：图产 `Rejected(reason,message)`，WorkflowExecutionStep
  写 `presetReply`+Proceed，OutputStep 跳 LLM。**不复用 DegradationScenario**（业务驳回≠系统失败，语义不混）。
  ✅ 用户确认。
- **P（并行语义）= P1 模型一轮回放多 tool_calls，顺序执行**：ToolCallExecutor 现有 loop 逐个执行（独立无依赖，
  顺序不改语义）；"并行"在模型调用层成立。✅ 用户确认。
- **D（图结构）= D1 一个参数化图 AfterSaleWorkflowGraph**：return/refund 同构，政策源+校验规则+提交服务参数化。✅ 用户确认。
- **路由机制 = @ToolChannel 注解**：声明式标在 @Tool 方法，ToolSchemaProvider 追踪 category，ToolExecutionStep 路由。
  ToolCallExecutor 返回结构化 `ToolCallResult`（契约变更，scripted 单测同步）。默认 COMPUTE 兼容既有计算工具。
- **RAG 工具返回 JSON {text,source}**：用户钦定"能标识知识库数据的 JSON 对象"，带 citation；ToolExecutionStep 拆 JSON。
- **Q（T3"活动政策"缺口）✅ 已定**：加第 6 个低风险 @Tool `PromotionPolicyTool`（@ToolChannel(RAG)，mock 活动
  政策文本+citation），满足 T3"活动政策"查询。复用既有 RAG 通道路由（零额外机制）。
- **R（政策类单入口 seam，前期）✅ 已定**：3 个政策 @Tool + DAG `query_policy` 节点全部委托 `PolicyQueryService`
  单 seam（`query(PolicyDomain)`，mock 实现）。后期接真 RAG 时加 `RagPolicyQueryService implements PolicyQueryService`
  委托 `HybridRetriever`（domain→知识域映射：RETURN→received_return_policy / REFUND→after_sale_policy /
  PROMOTION→promotion_and_member_policy），换实现不换 seam/调用方/工具 schema——单点改造。用户钦定"政策类后续改造
  为单入口接入真实 RAG，目前先做好前期"。

---

## 6. 风险与护栏

- **不手撸已提供能力**（铁律①）：图结构用 langgraph4j `StateGraph`（非手撸 topo）；@Tool schema 由
  `ToolSpecifications.toolSpecificationFrom` 生成；参数强转+反射调归 `DefaultToolExecutor`；不重写。
- **TDD 每切片 RED 先**（铁律②）：每片最小可跑通（fake/stub 驱真依赖原语），不一把梭哈。
- **统一收口**：子图写 context 强类型字段（workflowResult/presetReply/runtimeFacts），终态由顶层 PipelineExecutor 收口（④）。
- **source 门控**：工作流只采信 `LLM_WITH_POLICY_CONSTRAINTS`（refund/return 基线）；DETERMINISTIC_FALLBACK/null 不触发（同 [[high-risk-workflow-design]]）。
- **冒烟隔离**：真 smoke 须 export `SF_KEY`+`DS_KEY`；noop/路由单测用 ScriptedModelExecutor（不调真模型）。
- **契约变更影响面**：`ToolCallExecutor.execute` 返回类型 `List<String>`→`List<ToolCallResult>`，波及 `ToolCallExecutorTest`
  及 `ToolExecutionStep`（本计划内，TDD 同步更新）；`GatewayChatModel` 不受影响（仍消费 tool_calls）。
- **presetReply 安全**：canned 系统话术过 `securityFilter`（脱敏一致，非用户输入但保持链路一致）。
