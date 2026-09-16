---
name: high-risk-workflow-design
description: "高风险固定工作流(LangGraph)已实现(816绿,workflow包9测)：RoutePlan无DAG(用户纠正),DAG落点=高风险refund/return固定2节点StateGraph(submit→approval→END,Denied→Retry,Timeout→ShortCircuit);WorkflowExecutionStep@670@ConditionalOnProperty app.workflow.enabled;独立WorkflowApprovalDecision seam≠HitlDecision;真seam延后"
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-12T12:53:31.164Z
---

高风险固定工作流（LangGraph）——用户 2026-09-12 纠正方向后实现。原"per-intent 真 DAG（每意图动态 DagSpec）"已否决并回滚。

**⚠ 2026-09-12 扩展（[[business-tools-workflow-dag]]）**：2 节点 `RefundWorkflowGraph` 已被 6 节点 `AfterSaleWorkflowGraph` 替代为**活管线图**——return/refund 参数化共图（`query_user→query_order→query_policy→validate→submit_approval→approval_gate`），加 validate 校验节点（归属/存在/7天或30天窗口→`Reason` 枚举）+ E1 收口（`AfterSaleWorkflowOutcome.Rejected(reason,message)`→`WorkflowExecutionStep` 写 `presetReply`+Proceed，业务驳回≠系统失败不混 DegradationScenario；`OutputStep` 守卫见 presetReply 跳 LLM）。`AfterSaleWorkflow` seam（@FunctionalInterface）供单测 scripted；`WorkflowExecutionStep` 两 `@Qualifier` seam 按 `rp.intent()` 选图（return_request/refund_request）；`MAX_ITERATIONS=20`（langgraph4j 按 generator yield 计非节点数，10 在 6 节点图 retry 边界 trip）。`RefundWorkflowGraph`/`RefundService` 退役保留作已验证前身（plain 单测不依赖 Spring 仍 GREEN）。下文描述的是 2 节点前身（仍有效，作 langgraph4j StateGraph 最小范例）。

**纠正后架构（用户钦定）：**
- **RoutePlan = 每请求结构化路径计划，本身无 DAG**。低风险意图靠现有固定 @Order 链 + #135 自跳过，无 DAG。
- **DAG 落点 = 仅高风险操作（refund_request/return_request，requires_workflow=true）在 LangGraph 构建固定工作流子图**（非每意图动态生成）。
- **范围=仅高风险尾**：子图只跑 submit_refund + approval_gate（等人工）；tool（get_order_detail）/RAG（after_sale_policy）**留在主链**（#135 已工作，不重复）。无 capabilitiesExecuted 共存标志。

**实现（capability/workflow 包，9 测，816 全绿含 4 smoke skipped）：**
- `RefundWorkflowGraph`（plain class，2 节点 StateGraph）：`submit_refund`（RefundService.submit→写 context.workflowResult）→ `approval_gate`（WorkflowApprovalDecision.await→APPROVAL_KEY）。边 START→submit→approval；条件边 Approved→END / Denied→submit（Retry 改写重提）/ Timeout→END。复用 `NoCloneStateSerializer`+`AgentState`（携 PipelineContext，同 GraphNode.CONTEXT_KEY 模式），`maxIterations=10` 护栏（镜像 P14）。`invoke(PipelineContext)` **返回 `WorkflowApprovalDecision.Outcome`**（从终态读 APPROVAL_KEY）；异常抛 IllegalStateException。
- `WorkflowExecutionStep`（`@Component @Order(670) @ConditionalOnProperty(app.workflow.enabled=true)`，紧随 RagStep@660 先于 ContextBuilder@700）：守卫 `rp!=null && source==LLM_WITH_POLICY_CONSTRAINTS && requiresWorkflow` → invoke 子图；否则 Proceed（#135 source 门控同 RagStep/ToolExecutionStep，DETERMINISTIC_FALLBACK/null 不采信）。按终态收口：Approved→Proceed / Timeout→`ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT)`（不假装未审批退款成功，submit 已跑退款已提交但未获批准）/ Denied 终态（maxIterations 强制终止）→ShortCircuit(INTERNAL) / 异常→ShortCircuit(INTERNAL)。Java17 instanceof 链（非 pattern switch，后者 17 预览）。
- `WorkflowConfig`（`@Configuration @ConditionalOnProperty(app.workflow.enabled=true)`，镜像 LangGraphConfig）：NO_OP seams（RefundService 返 "WF-NOOP-{sessionId}" / WorkflowApprovalDecision 恒 Approved("auto")）+ RefundWorkflowGraph @Bean。
- `DegradationScenario.WORKFLOW_APPROVAL_TIMEOUT` 新增（"您的退款请求已提交，正在等待人工审批，请留意后续通知。"，语义区分 HITL_TIMEOUT）。
- `PipelineContext.workflowResult` 字段（additive，Slice 1 起；ContextBuilder/OutputStep 零改动，终态仍由顶层 PipelineExecutor 收口）。

**线性等价：** `app.workflow.enabled` 缺省=false → 零工作流 bean（@ConditionalOnProperty 不装配）→ @670 槽空 → 旧链 660→700 不变。回归 814（Slice 3）→816（Slice 4）全绿证。

**决策：** D=独立 `WorkflowApprovalDecision` seam（执行后审批 ≠ HitlDecision 执行前转人工，语义不混）；E=新增 WORKFLOW_APPROVAL_TIMEOUT（非复用 HITL_TIMEOUT）；F=sync NO_OP 最小（真 async webhook resume 延后）。

**延后/已知限制：**
- 真 seam（RefundService 调退款后端 / WorkflowApprovalDecision 真人工审批 + async resume）延后——本仓脚手架无退款后端/审批系统可接；同步阻塞 seam 无外部 resolver 仍为桩（加并发复杂度无实益），遵项目 NO_OP-seam 惯例（同 HitlDecision）。
- maxIterations 全 Denied→强制终止→INTERNAL 未显式测（Retry 边 Slice 2 已测；护栏镜像 P14 setMaxIterations）；denied-once-then-approved 证 Retry 自循环。
- eval workflow 拓扑断言待补（Slice 5 残项）。

关联 [[routeplan-design]]（缺口⑦已实现，⑥per-intent DAG 作废）、[[phase14-langgraph-design]]（P14 StateGraph/NoCloneStateSerializer 复用）、[[phase11-12-hitl-output-design]]（决策D，≠ HitlDecision）、[[degradation-and-eval-principles]]（话术短路+统一收口）、[[dont-hardwrite-use-dep-methods]]（铁律①复用 langgraph4j 非手撸 topo）。
