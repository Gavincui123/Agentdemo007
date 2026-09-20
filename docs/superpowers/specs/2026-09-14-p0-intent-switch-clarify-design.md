# P0 续跑意图切换 + 模糊意图澄清 + 售后并发 fork-join — 设计规格

- **日期**：2026-09-14（v2：2026-09-15 并入双意图并发设计）
- **状态**：设计已确认（v2 待用户复核），待转实现计划（writing-plans）
- **关联记忆**：[[segmented-systemprompt-intent-design]]、[[business-tools-workflow-dag]]（§2.5 多轮澄清续跑）、[[routeplan-design]]、[[degradation-and-eval-principles]]、[[phase6-7-understanding-intent-design]]、[[q1-nacos-prompt-management]]、[[q2-token-streaming]]

## 1. 目标与背景

**P0 bug（用户实测 turn5）**：用户「订单号是ORD-001，…算了直接给我退款吧」→ 系统回复「您的**退货**申请已受理」。用户最后明确要退款，系统却办了退货——**错误业务动作**。

**根因（已读码确认）**：
- `WorkflowResumeStep@595:59` `baselines.baselineFor(pending.get().intent())` —— 续跑时无条件恢复 **Turn 1 存下的 pending 旧意图**（return_request），不用当前轮意图。
- `RoutePlanStep@605:51` `if (context.routePlan() != null) return Proceed;` —— @595 预置 routePlan 后 @605 **跳过重路由** → 当前轮「退款」从未被识别。
- pending 意图**粘性**，盖过当前表述。

**用户钦定方向**：① 跟意图识别三层架构挂钩、**当前轮意图优先**（不信任旧 pending）；② **模糊意图做澄清动作而非猜**（不能靠关键词）；③ route LLM **显式标歧义**；④ entity-gate（无单号）flip-flop 歧义**一并纳入 P0**。

**v2 用户追加行为用例**（并发需求来源）：

| # | 例子 | 期望行为 |
|---|---|---|
| 1 | 后补单号「ORD-001」 | 跑 pending 退款 |
| 2 | 「这是我的订单 ord0001」 | 修饰语补单号也算补 → 退款 |
| 3 | 「ord001，我想看看耳机推荐」 | **并发**：「您的退款申请已提交，另外现在为您推荐…」 |
| 4 | 「ord-001 到哪了」 | 只答物流（放弃 pending） |
| 5 | 「退款订单号：ord-001，我最近想买耳机，推荐一款」 | **并发**（无 pending 的单轮双诉求）：退款 + 商品推荐合并回复 |

**v2 结论**：补单号识别**不用输入形状门槛**（长短/位置都分不清"到哪了"和"这是我的订单"），改由 **route LLM 带 pending 上下文判**；例 3/5 要求**真并行 fork-join**（用户钦定 A 方案非串行汇聚）+ **每腿独立降级**（某一路工具超时/失败→该路退化为客服话术，**无需告知客户原因**，另一路不受影响，统一收口合并回复）。

铁律遵循：话术短路 + 每步降级 + 统一收口；意图识别/决策路由用模型一律关思考；密钥经环境变量。

## 2. 已确认决策

1. **歧义信号**：`boolean ambiguous`（第 9 字段），LLM 显式置 true/false；澄清话术泛列售后菜单 + `intent_hint` 线索（无法点名冲突意图，接受此限制换稳健）。
2. **双意图信号**：`String secondaryIntent`（第 10 字段，**可空**）——仅「售后主意图 + 另有明确独立诉求」时填，供并发 fork（例 5）。
3. **`WorkflowResumeStep@595` 直接删除**（pending 读/删下移到 @670，@670 本就持 `pendingStore`）。
4. **`RoutePlanStep@605` 删 skip**（line 51-53）→ 续跑轮也重路由当前轮；**并注入 pending 提示**到 route prompt（§3.5）。
5. **`@670 WorkflowExecutionStep` 重写为状态机**（§5）：先判 ambiguous → 澄清；entity-gate / 单腿工作流 / 放弃 / **并发**分支。
6. **澄清话术 registry 化**：新 key `clarify-ambiguous`（`{{intent_hint}}` + `{{order_hint}}` 变量；硬编码兜底）。
7. **`ambiguous` 穿透 converge 兜底**（违例兜底到 baseline 时继承原 LLM 候选的 ambiguous）。
8. **并发 = 真并行 fork-join（A 方案）**：腿 1 工作流图异步提交 + 腿 2 第二诉求子管线（工具/RAG/组装/LLM），join 后统一收口合并回复（§7）。
9. **每腿独立错误边界**：腿 1 Timeout/Denied/异常 → 优雅话术（不再 ShortCircuit 断整条回复）；腿 2 工具/RAG/LLM 挂 → 优雅兜底话术；**审计不丢、话术不泄**（DegradationScenario 仍入审计）。
10. **订单号正则加宽** `(?i)ord-?\d+`（用户实测「ord001」「ord0001」小写无连字符现被漏）。
11. **Approved 确认话术带订单状态+政策结论**（例 5 话术钦定：图透出 query_order/validate 结果）。

## 3. route-LLM 显式信号

### 3.1 RoutePlanCandidate 加 `ambiguous` + `secondaryIntent`（第 9、10 字段）

```java
public record RoutePlanCandidate(
        String intent, boolean needsRag, boolean needsBusinessTools,
        List<String> requiredTools, List<String> knowledgeDomains,
        RiskLevel riskLevel, boolean requiresWorkflow,
        FallbackPolicy fallbackPolicy,
        boolean ambiguous,          // 9：LLM 标本轮多意图/反复/否定矛盾→true
        String secondaryIntent      // 10：可空；仅「售后主意图+另有独立诉求」时填（并发第二腿）
) { ... }
```

- `ambiguous` primitive，LLM 不输出→Jackson 默认 false；`secondaryIntent` 缺失→null（String 可空）。畸形（如 ambiguous="yes"）→ Jackson 抛 → 现有 catch → empty → rule 兜底（不单开容忍分支，YAGNI）。
- **构造点更新**：`RoutePlanBaselines` 全部 baseline 构造加 `, false, null`；显式构造 candidate 的测试同步。
- 加 `withAmbiguous(boolean)` 工厂（converge 兜底用，见 3.4）。
- `RoutePlan` 加 `ambiguous()` / `secondaryIntent()` 访问器（delegate）。

### 3.2 RoutePromptBuilder 加约束

prompt 增约束 + 两例：

- **ambiguous 约束**：「若用户本轮问题含**多个相互冲突或反复变更的意图**（如既退款又退货、『不要退货要退款』反复、否定矛盾如『不退款』），置 `ambiguous=true`；单一清晰意图置 `false`。`ambiguous=true` 时仍填你最可能的 intent/字段，但系统将改走澄清而非执行。」
- **secondary_intent 约束**：「仅当本轮含售后动作（refund_request/return_request）**且另有明确可独立执行的诉求**时，填 `secondary_intent`；只能是 order_query / refund_status_query / product_query / promotion_query / faq_query 之一；不得等于 intent；其余情况留空（null）。例：『退款订单号 ORD-001，想买耳机』→ intent=refund_request, secondary_intent=product_query。」
- FIELD_SPEC 增 `ambiguous`、`secondary_intent`。
- 示例补两条：`{"intent":"refund_request",...,"ambiguous":true,...}`（既问商品又提退款，冲突）；`{"intent":"refund_request",...,"secondary_intent":"product_query"}`（退款+买耳机，独立双诉求）。

### 3.3 RouteCandidateParser 容错

- `ambiguous`（boolean）/ `secondaryIntent`（String 可空）经 `treeToValue` 映射。畸形 → 现有 catch → empty → rule 兜底（路由永不崩；丢失信号则退化：ambiguous 丢→走清晰分支、secondary 丢→单腿，均非错误动作，②降级可接受）。

### 3.4 converge 穿透 ambiguous（关键）

`RoutePlanRuleMatcher.converge` 现状：采纳路径保留原 candidate（ambiguous 存活）✓；**违例兜底** `fallback(String intent, String reason)` 用 baseline 候选（ambiguous=false）→ 丢弃 LLM 的 `ambiguous=true` ✗。歧义候选更可能违例（LLM 困惑→tools/domains 易错），故须穿透：

- 改 `fallback(RoutePlanCandidate original, String reason)`，兜底候选 = `baselines.baselineFor(original.intent()).withAmbiguous(original.ambiguous())`。
- null 候选 → ambiguous=false（general_chat 兜底）。
- **`secondaryIntent` 不穿透**：违例兜底到 baseline（secondary=null）→ 退化单腿（只办售后、不答第二诉求），安全方向，明说接受。

### 3.5 pending 提示注入（RoutePlanStep → RoutePromptBuilder）

RoutePlanStep 读 `pendingStore`（新依赖，plan→workflow 包，见 §12），pending 非空时向 route prompt 注入：

> 「本会话有未完成的 {refund_request} 等待订单号。若本轮提供了订单号且未提出与之矛盾的新诉求，intent 应填 {refund_request}；若本轮另有明确诉求（如查物流、商品咨询），按本轮诉求填 intent，不要被未完成意图带跑。」

- `RoutePromptBuilder.build(history, query, pendingIntent)` 增可空参数（null=无提示，既有测试兼容）。
- 效果：裸单号/修饰语补单号（例 1/2）→ LLM 认 refund_request → 走分支 4 单腿；「到哪了」→ order_query → 分支 5；耳机诉求 → 分支 7 并发。**所有形状门槛（A/B/B'）作废**。
- LLM 判错退化路径：裸单号误判 order_query → 分支 5 答订单信息（摩擦，无错误动作）；「到哪了」误判 refund → 分支 4（错误动作风险——见 §11，real-SF 必验最高优先级）。

## 4. 订单号提取契约加宽

- `AfterSaleWorkflowGraph.java:94` `ORDER_ID_PATTERN` 改 `Pattern.compile("(?i)ord-?\\d+")`——覆盖 ORD-001 / ord0001 / ORD001 / ord-001（用户实测小写无连字符被漏）。
- `extractOrderIdFrom` 单点，entity-gate 与图内 query_order 共用，只改一处。
- 测试补：ord001 / ORD001 / ORD-001 / ord-001 各一例。

## 5. @670 状态机（重写）+ @595 删除 + @605 重路由

**删除 `WorkflowResumeStep@595`**（类 + 测试 + javadoc 引用清理：`RoutePlanStep.java:49-50`、`WorkflowExecutionStep.java:31,112`）。

**`RoutePlanStep@605` 删 line 51-53 skip** → 恒重路由当前轮（含续跑轮）+ 注入 pending 提示（§3.5）。同步删除 `RoutePlanStepTest.java:137 skipsWhenRoutePlanAlreadySet_resumeContinuation`（断言被删行为），改/补「pending 非空→prompt 含提示」断言。

**`WorkflowExecutionStep@670` `process()` 重写**（删现 requiresWorkflow 早退）。入参：`pending`（pendingStore.get）、`order#`（extractOrderIdFrom(rawInput)）、`rp`（context.routePlan，@605 已重路由含 ambiguous+secondary）。

**abandon 集**（订单主语问题/转人工类——当前轮优先，不并发、弃 pending）：
`{order_query, refund_status_query, security_request, degradation_request, low_confidence_query}`

决策顺序（**ambiguous 最先**，短路）：

| # | 条件 | 动作 |
|---|---|---|
| 1 | `rp.ambiguous()` | `setPresetReply(clarify-ambiguous(intent_hint, order_hint))` + **pending 保留** + Proceed（presetReply 守卫跳 LLM） |
| 2 | order#==null && rp∈{refund,return} && secondary==null | entity-gate 澄清（clarify-return/refund）+ `pendingStore.put(pending{rp.intent()})`（**覆盖**=当前轮优先）+ Proceed |
| 2b | order#==null && rp∈{refund,return} && secondary!=null | **并发**：腿1=澄清话术+put pending；腿2=secondary 子管线；合并（§7） |
| 3 | order#==null && rp∉{refund,return} | Proceed 主链，**pending 保留**（chit-chat 容忍；「算了不退了」靠 LLM ambiguous 拦截，见 §11） |
| 4 | order#!=null && rp∈{refund,return} && secondary==null | **先 remove pending** → 跑**当前**意图工作流（与 pending 不同=切换，**P0 核心修复**）；单腿 E1 收口不变（Approved/Rejected→presetReply；Timeout/Denied/异常→ShortCircuit） |
| 4b | order#!=null && rp∈{refund,return} && secondary!=null | **并发**：腿1=当前工作流（先 remove）；腿2=secondary 子管线；合并（例 5） |
| 5 | order#!=null && rp∈abandon集 && pending | **先 remove** + Proceed 主链（例 4：答物流，当前轮优先放弃 pending） |
| 6 | order#!=null && rp∈abandon集 && 无 pending | Proceed 主链 |
| 7 | order#!=null && rp∉{refund,return}∪abandon集 && pending | **并发**：腿1=pending 意图工作流（先 remove）；腿2=rp 子管线（例 3）；合并 |
| 8 | order#!=null && rp 其他非售后 && 无 pending | Proceed 主链 |
| — | `rp==null`（防御，路由永不崩） | Proceed（②降级） |

**先 remove 再 invoke**（分支 4/4b/7 统一）：工作流失败不残留旧 pending，防重试纯单号轮被分支 5/7 劫持回旧意图（失败后 pending 已清→重试「ORD-001」走主链答订单，安全不误动作）。

**注**：分支 1/2 的澄清仍发生在 @670（tools/RAG @650/@660 已先跑）——把澄清门前移省 tools/RAG 是 **P1**，不在 P0 范围（见 §11）。

## 6. 澄清话术 registry 化

- 新 AiService 模板 key `clarify-ambiguous`：

  > 「您的需求涉及多个方面{{intent_hint}}，请明确您当前需要：退款 / 退货 / 查订单 / 商品咨询？{{order_hint}}」

- `{{intent_hint}}`：`hintFor(rp.intent())` → refund_request→「（可能涉及退款）」/ return_request→「（可能涉及退货）」/ 其他→空。
- `{{order_hint}}`：**order#==null 时**渲染「（如涉及退款/退货，请一并提供订单号）」，有订单号时渲染空（branch 1 在有单号时也会触发，此时不应再要单号——§4 原 note 与 §6 矛盾的收口）。
- 两变量皆简单变量，无条件替换（`PromptTemplate.render` 缺失变量→空串已坐实），兼容 registry 限制。
- 硬编码兜底（②降级，同 clarify-return/refund 模式）。

## 7. 并发 fork-join（例 3/例 5/分支 2b）

### 7.1 两腿构成

| 触发分支 | 腿1（W=工作流） | 腿2（Q=第二诉求） |
|---|---|---|
| 4b | rp（refund/return），有单号跑图 | secondaryIntent |
| 7 | pending 意图，有单号跑图 | rp（非售后清晰意图） |
| 2b | 无单号→**降级为澄清话术**+put pending | secondaryIntent |

### 7.2 腿1：工作流图

- 图按 **W.intent** 选（现 `@Qualifier` 两图逻辑，`return_request`→退货图否则退款图）。
- **先 remove pending**（分支 4b/7），再 `executor.submit(graph.invoke(ctx))` → `Future<AfterSaleWorkflowOutcome>` 存 `PipelineContext.workflowFuture`，置 `concurrentMode=true`，Proceed（图不在请求线程跑——真并行；executor 复用 sseTaskExecutor 或新 bean，见 §12）。
- 并发模式下 **ShortCircuit 语义让位于每腿文本映射**（7.4）——腿1 任何结局都产出客户侧文本，不再中断整条管线。

### 7.3 腿2：子管线

- Q 的 routePlan = `RoutePlan.deterministic(baselines.baselineFor(Q))`（确定性基线即可——Q 是明确非售后诉求；baseline 已知非空）。
- 子 context = 克隆（同 sessionId/rawInput/history/request 元数据，routePlan=Q plan）。
- **`SubPipelineRunner` seam**（`PipelineExecutor` 线性/图两引擎各实现）：`runCapabilitySegment(subContext, 650, 700)` 跑 Tool/RAG/ContextBuilder 三步骤（子 context 携 `subRun=true` 标志，`WorkflowExecutionStep` 见标志恒 Proceed——650-700 区间本就无 @670，标志为图引擎安全网）。产出 **leg2Prompt**（组装好的 List<ChatMessage>）。
- 并发分支向 leg2Prompt 追加指令消息：「用户退款部分由系统另行回复，你只负责回答商品咨询部分，勿重复退款内容；若无检索结果请礼貌引导客户浏览其他商品，**勿提及系统问题**」。
- 腿2 子管线内失败**只影响腿2**（工具/RAG 步骤的 ShortCircuit 在子管线内捕获，映射为腿2 降级文本——7.4）。

### 7.4 每腿降级→客户话术映射（无需告知客户原因）

| 腿 | 结局 | 客户侧文本 |
|---|---|---|
| 腿1 | Approved | 确认话术（**带订单状态+政策结论**，§7.6） |
| 腿1 | Rejected | 驳回话术（r.customerMessage()，E1 语义不变） |
| 腿1 | Timeout / Denied / 异常 | 优雅话术：「退款申请已受理，正在审批中，请稍后查询进度。」（**不再** ShortCircuit(WORKFLOW_APPROVAL_TIMEOUT/INTERNAL) 断整条回复） |
| 腿2 | 工具/RAG 失败 | LLM 照常答（指令已含"无结果礼貌引导"）；LLM 也挂 → 硬编码优雅模板「目前暂未找到您想要的商品，您可以浏览店内其他商品或告诉我更多偏好。」 |
| 腿2 | LLM 异常 | 同上硬编码模板 |

- **审计不丢、话术不泄**：每腿结局仍记 log + DegradationScenario 入审计（`ChatTurnFinalizer`/集中式审计记录），只是客户侧文本优雅化（统一收口的延展）。
- 单腿分支 4 的 E1 语义**不变**（presetReply/ShortCircuit 原样）。

### 7.5 合并与流式（@800 OutputStep 并发分支）

- `concurrentMode=true` 时 OutputStep 走合并分支（presetReply 短路守卫前置判定顺序：presetReply 非空→短路不变；否则 concurrentMode→合并）。
- **await 腿1**（超时上限 30s 常量，配置化留后；超时按 Timeout 话术）→ `leg1Text = 7.4 映射(outcome)`。
- 腿2 = `chatRaw` / `chatRawStream`(leg2Prompt)（主模型，走现有主备容灾链；流式分支沿用 Q2）。
- 合并：`finalReply = securityFilter(leg1Text) + "\n" + securityFilter(leg2Text)`；SSE 序：**先 await 腿1 → emit 腿1 文本 → 转流腿2 tokens**（腿2 LLM 与 await 并行发起——真并行点）。
- 腿1 为澄清腿（分支 2b）：leg1Text=澄清话术，同样合并。

### 7.6 Approved 确认话术增强（例 5 话术钦定）

- 现 `workflowResult` 只存售后单号。图 **validate 节点**把订单状态（query_order 结果）与政策结论（query_policy/validate 结果）透出 → `AfterSaleWorkflowOutcome.Approved` 增 `orderStatus` / `policyConclusion` 字段（或图写 context 新字段，实现计划二选一，倾向 Approved record 扩展——invoke 返回承载，step 收口）。
- 确认模板带变量：`{{order_id}}/{{order_status}}/{{policy_conclusion}}/{{aftersale_no}}`，简单变量可接 registry（q1 confirmation 延后理由不再成立）。
- 例：「订单号 ord-001 当前订单未发货，满足退款政策，已为您提交退款申请审批请耐心等候。」
- 单腿分支 4 与并发腿1 共用此话术。

## 8. 数据流

```
Turn N（pending 存在或 not）
  @500 IntentRecognition → coarse intent
  @605 RoutePlanStep（删 skip，恒重路由；pending 非空→prompt 注入 pending 提示）
         LLM（关思考）产 10 字段候选（ambiguous+secondary）→ RouteCandidateParser
         → converge（采纳/兜底，ambiguous 穿透、secondary 兜底丢弃）→ context.routePlan
  @650 ToolExecutionStep / @660 RagStep（P0 仍后跑；并发腿2 走子管线再跑一次 Q 子集）
  @670 WorkflowExecutionStep 状态机：
         ambiguous → clarify-ambiguous 澄清 + pending 保留 + Proceed
         无单号+售后 → entity-gate 澄清 + pending + Proceed（secondary 非空→并发：澄清腿+子管线）
         单号+清晰 refund/return → 先 remove → 跑当前工作流（切换）（secondary 非空→并发）
         单号+abandon集 → 先 remove → 主链（答物流，当前轮优先）
         单号+非售后+pending → 并发：腿1 pending 工作流 ‖ 腿2 rp 子管线
  @700 ContextBuilder（主链；腿2 已由子管线单独组装）
  @800 OutputStep：presetReply 短路 / 并发合并（await 腿1→合并腿2）/ 常规 LLM
```

## 9. 边界与降级

- route LLM 不可用 → rule 兜底（baseline，ambiguous=false，secondary=null）→ 单腿非歧义分支，不阻塞（②降级）。
- LLM 产 ambiguous/secondary 畸形 → parser empty → rule 兜底（同上，secondary 丢=单腿）。
- ambiguous=true 但 converge 兜底 → §3.4 穿透保留 → 分支 1 澄清。
- secondary 因 converge 违例兜底丢失 → 退化单腿（只办售后），安全方向。
- 澄清话术 registry miss → 硬编码兜底。
- pending 未知 intent（不应发生）→ 清 pending 不续跑，Proceed（②降级）。
- 并发腿1 无单号（分支 2b）→ 腿1 降级为澄清话术 + put pending（图不跑）。
- 并发腿1 await 超时（30s）→ 按 Timeout 优雅话术。
- 腿2 子管线任何失败 → 腿2 降级模板，不影响腿1。
- abandon 集 + order# + pending → 先 remove（当前轮优先），注入/转人工意图不被并发分支误触发工作流（安全：安全轮永不提交售后动作）。

## 10. 测试策略（TDD，每步 RED→GREEN）

- **RouteCandidateParserTest**（扩）：ambiguous=true 解析/缺失→false/畸形→empty；secondary 解析/缺失→null/畸形→empty。
- **RoutePromptBuilderTest**（扩）：prompt 含 ambiguous+secondary 约束与双例；pendingIntent 非空→含提示文本、null→不含。
- **RoutePlanContractValidatorTest**（扩）：secondary 非空须 ∈{order_query,refund_status_query,product_query,promotion_query,faq_query} 且 ≠intent 且 ≠工作流意图，违→invalid（→兜底丢 secondary）。
- **RoutePlanRuleMatcherTest**（扩）：违例兜底时 ambiguous 继承原候选；secondary 兜底丢弃（退化单腿）；采纳时两者保留。
- **RoutePlannerTest**（扩）：LLM 候选 ambiguous/secondary 传播到 RoutePlan；LLM 挂兜底均无。
- **RoutePlanStepTest**：**删** skipsWhenRoutePlanAlreadySet（断言被删行为）；补 pending 非空→prompt 含提示。
- **AfterSaleWorkflowGraphTest**（扩）：order# 正则 ord001/ORD001/ORD-001/ord-001。
- **WorkflowExecutionStepTest**（重写为主）——状态机全分支：
  1. ambiguous→澄清话术+{{order_hint}}（无单号含要单号/有单号不含）+pending 保留。
  2. 无单号+售后（无 secondary）→entity-gate 澄清+pending 存（覆盖旧 pending）。
  3. 单号+清晰 refund（pending=return）→跑**退款**工作流（切换）+**invoke 前** pending 已删（**P0 核心断言**）。
  4. 单号+abandon集（order_query）+pending→pending 删+Proceed 不跑图（例 4）。
  5. 单号+非售后+无 pending→Proceed。
  6. 分支 2b/4b/7 并发触发：腿1 图异步提交（future 入 context）+腿2 子管线调用+指令追加；2b 腿1=澄清+put pending。
  7. 同意图续跑回归（pending=refund+当前 refund）。
  8. security_request+order#+pending→不跑图、pending 删。
- **WorkflowResumeStepTest**：随类删除。
- **OutputStepTest**（扩）：并发合并分支（阻塞：leg1Text+leg2Text 拼接+双 securityFilter；SSE：await→emit 腿1→转流腿2）；腿1 各结局→7.4 映射话术；腿2 LLM 挂→硬编码模板；await 超时→Timeout 话术。
- **AfterSaleWorkflowOutcome/Approved 模板**（新）：确认话术含 order_status/policy_conclusion 变量渲染。
- **SubPipelineRunner**（新）：线性/图两引擎 runCapabilitySegment 只跑 650-700、subRun 标志下 @670 恒 Proceed。
- **E1 回归**：单腿分支 4 的 Approved/Rejected/Timeout/Denied 收口不破。
- **real-SF 冒烟**（env-gated SF_KEY，单测不覆盖）——权威清单：
  1. P0 原 bug 全流程：退货→「算了退款 ORD-001」→ 退款确认（**最高优先**）。
  2. pending 提示诚实性：裸单号/修饰语补单号→refund_request；「ORD-001 到哪了」→order_query（**错误动作风险断言**）；耳机诉求→product_query/secondary。
  3. 例 3/例 5 并发全链路：合并回复两段俱全。
  4. 例 4：只答物流，无退款提交。
  5. jumble/flip-flop/否定矛盾 → ambiguous=true 可靠性。

## 11. 已知风险与限制

- **route LLM 三担**：ambiguous、secondary、pending 提示遵循——route 模型是已知弱链（[[business-tools-workflow-dag]] T4 caveat）。「到哪了」误判 refund_request → 错误动作（分支 4），real-SF 冒烟第 2 项是最高风险断言；prompt 中"另有明确诉求按诉求判"须写重。
- **腿1 变慢**（真退款后端接入后）：SSE 首 token 延迟 = await 时间；后续优化=先流腿2 或流式前缀（不在 P0）。
- **secondary 兜底丢失** → 退化单腿不答第二诉求（安全但漏答，文档化）。
- **「算了不退了」**（无单号、LLM 未标 ambiguous→general_chat）走分支 3 pending 保留，后续纯单号会复燃退款——依赖 route LLM 对否定矛盾标 ambiguous（§3.2 prompt 已教），列入冒烟第 5 项。
- **澄清仍在 @670（P0 不含 P1）**：歧义/澄清轮 tools/RAG @650/@660 仍先跑（白跑延迟）。前移澄清门到 @606 省 tools/RAG = P1，单列。
- **boolean ambiguous 不能点名冲突意图**：澄清话术泛列菜单 + intent_hint 线索。若需点名，后续升级为 detectedIntents 列表。
- **@670 重写面较大**：删 requiresWorkflow 早退 + 10 分支状态机 + 并发 fork-join + pending 读/删下移。须保 E1 收口与 #135 门控不破。

## 12. 涉及文件

- 改：`RoutePlanCandidate.java`（+ambiguous/+secondaryIntent/+withAmbiguous）、`RoutePlan.java`（+访问器）、`RoutePromptBuilder.java`（约束+例+pendingIntent 参数）、`RouteCandidateParser.java`（注释更新）、`RoutePlanRuleMatcher.java`（fallback 签名+ambiguous 穿透）、`RoutePlanContractValidator.java`（secondary 约束）、`RoutePlanStep.java`（删 skip+注入 pendingStore 提示）、`WorkflowExecutionStep.java`（状态机+并发 fork+PromptRegistry 已在）、`RoutePlanBaselines.java`（baseline 构造 +false,null）、`AfterSaleWorkflowGraph.java`（正则+validate 结果透出）、`AfterSaleWorkflowOutcome.java`（Approved 增字段）、`PipelineContext.java`（workflowFuture/concurrentMode/leg2 产物）、`PipelineExecutor`+两引擎（SubPipelineRunner seam：runCapabilitySegment）、`OutputStep.java`（合并分支）、`WorkflowConfig.java`（executor bean 如复用 sseTaskExecutor 则不改）、Nacos AiService 模板 `clarify-ambiguous`。
- 删：`WorkflowResumeStep.java` + `WorkflowResumeStepTest.java`；`RoutePlanStepTest.skipsWhenRoutePlanAlreadySet`。
- 测：§10 全清单。
- 依赖注：RoutePlanStep 新增 `PendingWorkflowStore` 依赖（plan→workflow 包，capability 间耦合，接受）。
- 不改：`application.yml`（无新配置项；await 上限 30s 常量留后配置化）。

## 13. 不做（YAGNI）

- `detectedIntents` 列表（点名话术）——留后升级（§11）。
- 澄清门前移到 @606 省 tools/RAG——P1 范围。
- 纯订单号"ORD-001"的意图识别强化（交给 route LLM + pending 提示，不另建规则）。
- 否定语义解析（"不要退货"）——交给 route LLM 判断，不自建否定词典。
- 补单号形状门槛（A/B/B'）——作废（route LLM pending 提示取代）。
- **双非售后并发**（如"查物流+推荐耳机"无售后）——仅售后+诉求并发，不泛化。
- 腿2 直调工具/RAG 组件——走 SubPipelineRunner 子管线（复用分派逻辑）。
- 先流腿2 / 流式前缀（腿1 慢时优化）——留后。
