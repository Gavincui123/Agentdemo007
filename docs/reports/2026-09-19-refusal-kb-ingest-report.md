# 拒答机制 + RAG 录入 + 元数据管理 交付报告（2026-09-19）

> 范围：任务1 拒答机制落地各分支 / 任务2 RAG 在线录入（多类型文件清洗切块）/ 任务3 录入元数据（版本·命名空间·权限）。
> 验证口径：后端 1213 测试 0 失败（其中本次新增 11 个测试类 56 例）、前端 vue-tsc + 83 vitest + vite build 全绿、/admin/kb 全生命周期 curl 冒烟 + 浏览器页面实测。
> 配套记录：走查发现的缺陷清单见 §7（memory/post-task-compact-review.md 为本次反思流程沉淀）。

---

## 1. 任务1：拒答机制（无依据不编造）

### 1.1 设计要点

拒答 = 业务级正确行为，与系统降级语义正交（**不标记 degraded**），分四层落实：

| 层 | 机制 | 位置 |
|----|------|------|
| 信号层 | RAG 漏斗终态零片段（空召回/终闸不达标/注入扫描清空）→ `context.groundingMiss=true`；闲聊/计划免 RAG 的正常跳过**不置位** | `RagStep.skipRag()` |
| 工具层 | 工具连通但未命中数据：RUNTIME 工具无数据文案（`NoDataSignals` 子串识别）附加路由 `context.toolDataMisses`；RAG 政策工具未命中（`PolicyFragment.hit=false`）兜底话术不再混入 ragFragments | `ToolExecutionStep.routeResults()` |
| 裁决层 | `RefusalGateStep@Order(690)`（证据全集到齐后）：`groundingMiss` 且 ragFragments/runtimeFacts/toolResults **全空** 且无 presetReply/concurrentReply 且意图不在免拒答集合（CHIT_CHAT/REASONING/LONG_CONTEXT/STRUCTURED_EXTRACTION/TRANSFER_TO_HUMAN/INJECTION）才构成"无依据" | `capability/refusal/` |
| 提示词层 | `ObjectiveDataLayer` 新增「工具无数据」框定块（必须如实告知未查到、严禁相近数据顶替）；`SystemAnchorLayer` 依 `groundingMiss` 注入"严禁自身知识补答"约束；DEFAULT_SYSTEM_PROMPT 增加拒答边界 | `context/` |

裁决两模式：
- **prompt**（默认）：Proceed，拒答约束交终答 LLM 执行（不破坏既有评测基线，rag.json 仍期望 DEGRADE+RAG_SKIP）；
- **strict**：写 `presetReply` 拒答话术零 LLM 短路 + `AuditEventType.REFUSAL` 审计事件。

工具无数据 ≠ 拒答：工具返回的"订单不存在"是**真实负事实**，保留 runtimeFacts 高置信通道，按"如实告知未查到 + 提示核对/转人工"作答，只有 RAG/工具全空才触发拒答话术。

### 1.2 涉及文件

- 新增：`capability/refusal/RefusalProperties.java`、`capability/refusal/RefusalGateStep.java`、`capability/tool/NoDataSignals.java`
- 修改：`common/pipeline/PipelineContext.java`（toolDataMisses/groundingMiss 字段）、`capability/tool/ToolExecutionStep.java`、`capability/business/PolicyFragment.java`（+hit 标志）、`MockPolicyQueryService/RagPolicyQueryService`（兜底 hit=false）、`capability/rag/RagStep.java`、`context/ObjectiveDataLayer.java`、`context/SystemAnchorLayer.java`、`observability/audit/AuditEventType.java`（+REFUSAL）、`application.yml`（app.refusal）

## 2. 任务2：RAG 在线录入（多类型文件 → 清洗 → 切块 → 入库）

### 2.1 多类型文件解析（`capability/kb/parse/`）

| 类型 | 解析器 | 说明 |
|------|--------|------|
| md/markdown | MarkdownTextParser | 标题层级栈，# 建面包屑，首 H1/H2 为标题 |
| txt | 直读 | 纯文本 |
| html/htm | HtmlTextParser | 跳过 script/style/nav 等，表格→"a \| b"行，h1-h6 层级 |
| pdf | PdfTextParser（PDFBox 3.0.5） | 逐页"第N页"节段；**扫描件/纯图片提取为空 → 拒绝入库并提示走 Python OCR 流水线** |
| docx | DocxTextParser（POI 5.4.1） | Heading/标题 样式映射 1-6 级 |
| xlsx/xlsm | XlsxTableParser（POI 5.4.1） | DataFormatter 取值，每表上限 2000 行 |
| csv/tsv | CsvTextParser | RFC4180 迷你解析，表头:值 成对，上限 5000 行 |
| json | JsonStructuredParser | 数组→"条目#i"，扁平化 key: value |

统一清洗（`TextCleaner`）：NFKC 归一、LF/nbsp/零宽字符、控制字符、页码行剔除、空白规整。

### 2.2 专业领域切块双策略（`capability/kb/chunk/ChunkingService`）

- **条款感知自动切换**：正文命中"第X条/章节、1.2.3、（一）、1、"等编号模式 ≥3 处 → 条款模式，**条款原子不硬切**；超长单条内部递归切分时加"（承接第X条）"锚定原条款；
- **通用递归切块**：段落打包 + 短尾合并（≤max/4），分隔符梯度兜底；
- 面包屑 `【title › 章 › 节】` 嵌入向量文本首行（增强嵌入 + 命中可溯），已去重防标题重复；
- 参数可配：`KB_CHUNK_MAX_CHARS`（默认 500）/ `KB_CHUNK_OVERLAP`（默认 80）。

### 2.3 录入主链（`capability/kb/KbIngestService`）

扩展名白名单 → SHA-256 指纹 → 解析 → 清洗（可提取字符数=0 拒绝）→ 切块 → `dryRun` 试运行截断（不触库不索引）→ 换版收口（旧 ACTIVE 版 SUPERSEDED + 按精确 source 清单广播删除稠密+稀疏向量）→ 向量索引（复用 VectorStore seam，dev InMemory / 真库 Chroma 同路）→ kb_document+kb_chunk 落库 → 目录快照刷新。
嵌入失败 → 异常上抛整单拒绝。

### 2.4 管理端点（`admin/KbAdminController`，`/admin/kb/**`，X-Admin-Token 鉴权）

| 端点 | 说明 |
|------|------|
| `POST /admin/kb/preview` | 试运行：解析→清洗→切块预览，不入库不索引 |
| `POST /admin/kb/documents` | 正式录入（同 namespace+docNo 重灌即换版） |
| `GET /admin/kb/documents` | 文档台账（namespace/status 过滤） |
| `GET /admin/kb/documents/{id}` | 文档详情 + 全部切块 |
| `DELETE /admin/kb/documents/{id}` | 下架（幂等）：删向量 + DELETED + 快照移除 |

### 2.5 跨通道向量删除

新增 `capability/rag/SourceDeletableStore.java` seam（按 source 精确删除，-1=不支持计数），InMemoryVectorStore / ChromaVectorStore（`where source $in`）/ LuceneBm25IndexService（Term 删除+commit+NRT refresh）三实现，换版/下架稠密稀疏同步生效。

## 3. 任务3：录入元数据（版本·命名空间·权限）

### 3.1 文档版本管理

- 业务键 = namespace + docNo，version 自 1 单调递增；**重灌即换版**：旧版 SUPERSEDED + 向量即时删除（检索只见最新版），DB 保留全版本历史可回溯（versionNote 记录本版改动）；
- 片段级 source 契约（`KbSourceRef`）：`docUid = kb:{namespace}:{docNo}`（docNo 消毒防前缀歧义），`source = {docUid}:v{version}#{seq}`；非 `kb:` 前缀（dev 种子/Python 流水线语料）为库外历史片段，不参与版本管理；
- 持久层：`KbDocumentEntity`（status 状态机 ACTIVE→SUPERSEDED/DELETED，收口 markSuperseded/markDeleted）/ `KbChunkEntity`（片段级持久副本，换版/下架删向量的依据）；prod 由 schema.sql 幂等建表。

### 3.2 命名空间与权限过滤

- `KbNamespace`：PUBLIC（人人可检索）/ PRIVATE（仅 allowedPrincipals 名单主体，逗号分隔，匹配 `PipelineContext.userId`；匿名/eval 仅 PUBLIC）；
- `KbCatalogService` 内存快照（docUid→namespace+principals）：启动全量加载、录入/下架增量刷新，RagStep 漏斗**宽召回后、粗滤前**过滤（无权片段不占重排/注入名额）；管理台直查 DB 不经快照；
- 向后兼容：非托管来源恒放行，存量知识不因新权限模型消失。

## 4. 前端交付

- 新增 `frontend/src/api/kb.ts`（强类型对齐后端 record 投影，FormData 上传，X-Admin-Token 注入）；
- 新增 `frontend/src/views/kb/KbView.vue`：录入表单（文件/文档号/标题/命名空间/私有名单/知识域/版本说明）+ 试运行切块预览表格 + 正式录入 + 文档台账（命名空间过滤、状态徽标 生效中/已换版/已下架）+ 详情切块列表 + 下架；
- 路由 `/kb`（meta 知识库）+ App 导航「知识库」，纳入 ADMIN_GUARDED；
- 顺手修复：`ChatView.vue` 既有未使用变量 `gateChipText`（阻塞 vue-tsc 构建）。

## 5. 需要配置的内容

### 5.1 本次新增（application.yml `app.refusal` / `app.kb`，均有默认值可零配置启动）

| 环境变量 | 配置键 | 默认 | 说明 |
|----------|--------|------|------|
| `REFUSAL_MODE` | `app.refusal.mode` | `prompt` | 拒答模式：`off`/`prompt`（提示词约束，默认）/`strict`（零 LLM 短路拒答话术）。**生产建议 strict**（见 §7 第 4 条修复后开启更稳） |
| `REFUSAL_PHRASE` | `app.refusal.phrase` | 默认拒答话术 | strict 模式短路返回的话术，可按业务口吻替换 |
| `KB_CHUNK_MAX_CHARS` | `app.kb.chunk.max-chars` | `500` | 切块上限字符数（条款模式下单条超限才内部递归切） |
| `KB_CHUNK_OVERLAP` | `app.kb.chunk.overlap` | `80` | 兜底切块重叠字符数 |
| `KB_PREVIEW_LIMIT` | `app.kb.preview-limit` | `50` | 试运行返回的切块预览条数上限 |

### 5.2 KB 生效前提（既有配置，需按环境确认）

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `VECTORSTORE_TYPE` | `inmemory` | 真库 RAG 须 `chroma`（本地启动注意 Nacos 远程配置会强制 chroma + 不可写 LUCENE_INDEX_DIR，需 `SPRING_CONFIG_IMPORT=` 空跳过，见 §8） |
| `EMBEDDING_ENABLED` | `false` | chroma 模式必须 `true`，且嵌入模型与入库语料完全一致（SiliconFlow Qwen3-Embedding-8B，4096 维），换模型须全量重灌 |
| `CHROMA_BASE_URL` / `CHROMA_COLLECTION` / `CHROMA_TOKEN` | `http://localhost:8001` 等 | Chroma v2 连接参数 |
| `LUCENE_INDEX_DIR` | `./data/lucene-bm25` | 真库模式 BM25 磁盘索引目录，**进程须可写** |
| `agentdemo.admin-token` | dev `dev-admin-token` | 前端「知识库」页与 `/admin/kb/**` 鉴权（请求头 X-Admin-Token），**生产必须改** |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `10MB` / `50MB` | 上传上限，大文件按需调大 |

### 5.3 无需操作

- 数据库：dev H2 `ddl-auto=update` 自动建 `kb_document`/`kb_chunk`；prod MySQL 由 `schema.sql`（CREATE TABLE IF NOT EXISTS）每次启动幂等执行；
- 前端：构建产物进 jar，路由/导航已内置，无新增前端配置；
- Nacos：`app.refusal.*`/`app.kb.*` 为普通 Spring 属性，如需热更新可放入远程配置（非必需）。

## 6. 验证结果

| 项 | 结果 |
|----|------|
| 后端全量 | **1213 tests, 0 failures**（新增 11 测试类 56 例：RefusalGateStep 8 / NoDataSignals 2 / PolicyFragment 4 / RagStepGroundingMiss 5 / ObjectiveDataLayerRefusal 3 / ChunkingService 6 / KbSourceRef 4 / KbCatalogService 5 / TextCleaner 6 / DocumentParsers 6 / KbIngestServiceFlow 7） |
| 前端 | vue-tsc 类型检查 + 83 vitest + vite build 全绿 |
| API 冒烟 | /admin/kb 全生命周期（preview → ingest → 台账/详情 → 重灌换版（旧向量删除）→ 下架（幂等））实测通过 |
| 页面实测 | 浏览器 DOM 级验证 /kb 页录入-预览-台账交互 |

## 7. 反思 Review 发现（缺陷清单，待修复决策）

> 全绿 ≠ 无缺陷。按严重程度分级，1/2 建议必修，3/4 视部署形态决定。

**较重要（真实缺陷）**
1. **录入主链无事务 + 换版先于索引**（KbIngestService.ingest）：嵌入失败窗口内旧向量已删、旧版已 SUPERSEDED、新版未落库 → 该文档检索侧消失直到重灌。建议改为"索引新版→落库→最后删旧收口"（失败方向反转可自愈）+ 补 @Transactional。
2. **版本号读-改-写无唯一约束**：并发重灌同一 docNo 可产生重复同版本 ACTIVE 行。建议 UNIQUE(namespace, doc_no, version) + docUid 粒度锁。
3. **目录快照多副本盲区**：多实例部署下新录文档在其他实例不可见（fail-closed）、PRIVATE 权限收紧不扩散（安全相关滞后）；且 loadAll 失败时 PUBLIC 托管文档也会被滤掉，warn 日志话术与实际行为不符。单实例 demo 可接受。
4. **链路异常被当作"知识未命中"**：RagStep catch(Exception) 也置 groundingMiss，strict 模式下 Chroma/Lucene 宕机会把系统故障包装成"知识库暂无资料"。建议异常跳过不置位（或区分标志）。**生产开 strict 前建议先修此条。**

**次要（边界与一致性）**
5. NoDataSignals 子串匹配有误伤面（真实数据含"不存在"会同时进 runtimeFacts 与 toolDataMisses，框定头与数据同现指令矛盾）；长期应结构化未命中标记。
6. `KbChunkRepository.deleteByDocumentId` 死代码（注释"下架清库"但从未调用）。
7. KbAdminController 录入兜底 catch 吞异常不落日志（生产排查难）；list 的 valueOf 不校验，非法值 500 而非 400。
8. `createdBy` 恒 null（未接管理台令牌主体）。
9. checksum 只审计未去重（同文件重传也会 +1 版本）。
10. KbSourceRef javadoc（声称白名单）与实现（黑名单替换 `[\s:#|]+`）不符；全角"："不在替换集（无功能影响）。
11. XLSX/CSV 超限行静默截断，预览结果无 truncated 提示。
12. `kb_chunk.text` 为 TEXT（64KB），`KB_CHUNK_MAX_CHARS` 调到 2 万+ 字符会撞 utf8mb4 上限（默认 500 无风险）。

## 8. 已知限制

- **PRIVATE 命名空间的主体身份**：权限匹配 `PipelineContext.userId`，当前工具侧 userId 固定 `U100`（既有已知限制）——PRIVATE 真正生效需先接入真实用户身份透传。
- **扫描件/纯图片 PDF**：Java 侧提取文本为 0 直接拒绝，OCR 属 Python 流水线（scripts/rag-ingest）能力，两条录入通道并存。
- **本地启动环境坑**：Nacos 远程配置会强制 `vectorstore.type=chroma` + `LUCENE_INDEX_DIR=/var/lib/agentdemo007`（macOS 不可写），本地离线启动需 `export SPRING_CONFIG_IMPORT=`（dev yml 自带说明）。
- 录入与 Python 流水线（scripts/rag-ingest）的关系：Java 在线通道面向管理台即传即用（8 类文件 + 版本/权限元数据）；Python 离线通道面向批量语料初始化（OCR/父子块/嵌入缓存），两者经"同嵌入模型 + source 契约"共存，`kb:` 前缀区分归属。

---

## 附记：高风险工作流生产化改造（2026-09-19 深夜·用户裁决驱动）

> §7 反思 review 后用户结合运行日志实测追加两条裁决：① 高风险操作面向生产，审批不能恒批准（NO_OP），
> 必须建人工工单走管理台决议（评测集 HITL 工单链路已验证可拉起可决议，复用该机制）；
> ② 资格判定不能硬编码窗口规则，必须把**政策知识 + 订单实时事实**一并交给 Agent 裁决。

### A. 审批真工单化（恒批准 NO_OP 退役）

- `WorkflowApprovalDecision` seam 重写：`await(ApprovalRequest)`（审批上下文富集：动作/订单/售后单号/订单摘要/政策结论/Agent 资格判定依据/用户申请），终态新增 `Pending`；
- 新增 `TicketWorkflowApprovalDecision`：请求内建 PENDING 人工工单（复用 `HumanTicketService` 状态机+DB 持久+幂等守卫，业务键 `wfa:{action}:{orderId}`）+ `WorkflowApprovalBridge` 桥等待——管理台 confirm/reject 决议即唤醒工作流线程（confirm 前业务门对账照常：退款查支付状态/退货查物流状态，`HitlBusinessGate` 扩展解析 `wfa:` 前缀）；
- **等待窗口**（`WORKFLOW_APPROVAL_WAIT_SECONDS`，默认 120s）：窗口内决议→实时回话术；耗尽→`Pending` 终态——工单保持 PENDING 交管理台，客户如实回"已提交人工审批"（不假超时、不假批准）；窗口外决议为纯状态转移（带外通知属生产侧后续能力）；
- **人工驳回=终局**：图内 Retry 重提退役（人工否决后重提属重新申请），`AfterSaleWorkflowGraph` approval 边简化为直落 END；`resume` 端点对工作流单显式 409（无检查点恢复入口）；
- 管理台路由：`AdminHitlController.resolve` 先查桥（有等待线程→唤醒并跳过检查点恢复；无→原恢复路径），HITL 检查点单行为零变化。

### B. 资格判定 Agent 化（硬编码窗口规则退役）

- 删除 `AfterSaleValidationRule`/`ReturnValidationRule`（7 天）/`RefundValidationRule`（30 天）及旧 2 节点 `RefundWorkflowGraph`（含其测试）；`Reason` 枚举 `BEYOND_7_DAY`/`REFUND_WINDOW_EXPIRED` → `POLICY_INELIGIBLE`；
- 新增 `AfterSaleEligibilityJudge` seam + `LlmAfterSaleEligibilityJudge` 实现：提示词分栏「订单实时事实」（订单号/下单时间/状态/商品/金额/**当前日期**（注入 Clock））与「政策知识」（`query_policy` 召回原文；兜底政策不冒充知识），要求只依据所给材料裁决、政策未覆盖转人工、禁止编造条款；输出严格 JSON 三态 `ELIGIBLE/INELIGIBLE/UNCERTAIN`（`readTree` 宽松解析，容忍围栏/前后文字）；
- 机械事实校验（订单存在/归属）保留代码内（非政策判断），短路先于裁决；
- 降级语义：LLM 未装配/失败/空回复/输出不合法（含 INELIGIBLE 缺客户话术、decision 越界）→ 一律 `UNCERTAIN` fail-safe 到人工审批——**裁决失败绝不冒充业务驳回、绝不盲目放行**。

### C. 话术与细节修正（对应用户指出的两个问题）

- Approved 话术："已提交审批，请耐心等候"（与恒批准自相矛盾的空头承诺）→ "已审批通过，我们将尽快为您处理"；新增 Pending 话术"已提交人工审批，审批结果将另行通知"；Denied 话术"经人工审核未能通过…可联系人工客服"；
- 售后单号不再泄露内部实现：`WF-NOOP-…` → `WF-` + 随机 8 位（submit 真后端仍为 dev 桩，迭代后补）。

### D. 配置变更

| 环境变量 | 默认 | 说明 |
|----------|------|------|
| `WORKFLOW_APPROVAL_WAIT_SECONDS` | `120` | 请求内审批等待窗口（秒）；`app.workflow.approval-wait-seconds` |

### E. 测试

- 重写 `AfterSaleWorkflowGraphTest`（机械校验/Agent 三态/审批终态/上下文富集/驳回终局不重提）；`WorkflowExecutionStepTest` 更新 Denied→话术短路、新增 Pending 用例；`AdminHitlControllerTest` 新增工作流单决议路由 3 用例；
- 新增 `TicketWorkflowApprovalDecisionTest`（建单内容/桥唤醒/窗口耗尽工单保持 PENDING/同键幂等复用/迟到决议）、`LlmAfterSaleEligibilityJudgeTest`（三态解析/围栏宽容/非法输出降级/提示词契约钉：实时事实+政策知识+当前日期一并交 Agent）。

## 附记 F：提交制审批（2026-09-20·用户裁决：审批是事件，Agent 最小权限）

反思级 review（附记 A–E 交付后的走查）产出 1 个 P1（并发腿 30s await vs 90s 审批窗口互不知情，
人工决议结果被静默丢弃）与多个 P2（90s 阻塞叠裁决 LLM 耗时逼近 SSE 120s 预算、桥共享 future
unregister 竞态、腿1 克隆上下文缺 standardQuery、裁决提示词注入面）。用户裁决不修等待链，而是
**拆掉等待链**——审批模型整个翻转为「提交制」：

### F1. 原则（用户裁决原文要点）

1. 人工批准/驳回是一个**事件**（工单状态变更），不能被任何超时影响；
2. Agent 只负责**创建工单**并告知用户"已提交人工审批"（最小权限）；
3. 业务流程是**业务系统内部运作**（批准事件由业务系统消费执行，非 Agent 链路）；
4. Agent 对结果的感知只有一条路：**查询工单状态**（新增 Mock 工具），HITL 处理完后只告知进度；
5. 其余 review 修复以该原则为基础落地。

### F2. 等待链整体退役（溶解 3 个 P1/P2）

- 删除 `WorkflowApprovalBridge`（桥唤醒）、`TicketWorkflowApprovalDecision`（请求内等待窗口）、
  `WorkflowApprovalDecision`（await seam）、`AfterSaleSubmitService`（图内提交桩）；
- `app.workflow.approval-wait-seconds` / `WORKFLOW_APPROVAL_WAIT_SECONDS` 配置退役；
- P1（腿1 30s vs 窗口 90s）、P2（90s+SSE 预算叠加）、P2（桥竞态）随等待链**自然溶解**——
  不存在请求内等待，就不存在"超时影响决议"。

### F3. 提交制新链路

- `WorkflowApprovalSubmitter` seam + `TicketApprovalSubmitter` 实现：建 PENDING 人工工单
  （`wfa:{action}:{orderId}` 幂等键）后**立即返回**；同键 PENDING 复用、APPROVED 已受理不重建
  （防重复业务动作，回"此前已通过"话术）、**REJECTED/TIMEOUT 允许重建新单**（驳回是那张工单的
  事件，不封禁客户再次申请——顺带修复 review 中"REJECTED 永久锁死重申请"的 P3）；
- 图收缩为 **5 节点**：`query_user → query_order → query_policy → validate →(pass) submit_ticket → END`；
  `AfterSaleWorkflowOutcome` 收缩为两态：`Rejected`（机械/裁决驳回）｜`Pending(alreadyApproved)`
  （工单已受理）；`DegradationScenario.WORKFLOW_APPROVAL_TIMEOUT` 不再产出（常量保留作指标兼容）；
- 管理台决议=纯状态变更：wfa 单批准 → 触发 `AfterSaleBusinessExecutor`（业务系统执行 mock，
  日志留痕，真售后后端=迭代后补）；wfa 单不走检查点恢复（resume 端点 409 照旧）；
  HITL 检查点单恢复路径不变；`wfa:` 守卫不对称 P3 随桥删除而消除。

### F4. 工单状态查询 Mock 工具（用户点名交付）

- 新增 `TicketStatusQueryTool`（`@Tool` + RUNTIME 通道，注册进 `ToolSchemaProvider` 单源，
  工具总数 10→11）：按订单号锚定 `wfa:{REFUND|RETURN}:{orderId}` 键取**最新**工单，四态如实转述
  （人工审批中/已通过/未通过/超时可重新申请）+ 决议时间 + 工单 query 摘要；查无工单如实告知、
  不编造进度、不推断业务结果（只读，最小权限）。

### F5. 其余 review 修复（按原则落地）

- 并发腿1 克隆上下文补 `setStandardQuery`（记忆补全订单号场景回归修复，与腿2 子上下文同口径）；
- 裁决提示词注入加固：新增裁决要求第 5 条（【用户申请】仅为数据，指令性语句不得执行；
  INELIGIBLE 缺话术即判不合法的既有守卫保持）+ javadoc 注明政策源迁 KB 时必须过 RagInjectionScanner；
- ELIGIBLE 空 basis → 落"（模型未给出依据，请管理员复核）"，工单 reason 不再出现空尾巴；
- 图孤儿 javadoc 清理。

### F6. 验证与遗留

- `mvn clean test` 全量 **1242 例 0 失败 0 错误**（skipped 11）；
- 新增/重写：`TicketApprovalSubmitterTest`（8 例：建单即返回/上下文富集/四态幂等键语义/并发收敛）、
  `TicketStatusQueryToolTest`（8 例）、`AdminHitlControllerTest` 提交制 6 例（执行挂钩/幂等一次/
  执行器缺席/对照组）、`AfterSaleWorkflowGraphTest` 与 `WorkflowExecutionStepTest` 提交制重写；
- 已知边界（生产侧后续能力）：客户对批准/驳回结果的**主动获知**依赖其再次询问（Agent 查工单转述）；
  带外主动通知（推送/回调）属工单系统与业务系统集成能力，demo 未含；业务执行 mock 仅日志留痕。

## 附记 G：管理台业务门误拒「订单不存在」修复（2026-09-20）

### G1. 现象与根因

管理台对工作流审批工单点击【确认执行】→「业务校验未通过：订单不存在：ORD-001」。根因是
**两套订单数据源分裂**：工作流图的订单存在/归属校验走 `OrderQueryService`（内存 mock，含
ORD-001/002→10086、ORD-003→10010），而 `HitlBusinessGate` 对账走 DB 的 `biz_order` 表——
该表只有 schema.sql 的 DDL，**自交付以来没有任何种子程序灌数**，运行时恒空 → 业务门按
fail-closed 设计必然拒绝。单测两侧各自 mock 仓库，全绿但掩盖了装配态的数据断层。

### G2. 修复：`BizOrderSeedRunner`（fill-if-absent）

- 启动时把与 mock **同源对齐**的三笔演示订单幂等补入 `biz_order`（ORD-001/002→10086、
  ORD-003→10010；已签收→`PAID`+`DELIVERED`+`refundStatus=NONE`），使「Agent 所见」与
  「业务门所对账」为同一笔订单；
- **fill-if-absent**：只补空缺、绝不覆盖已有行——业务表是对账锚点，真实数据/已流转状态
  （如 REFUNDING）一律以表中现值为准；
- 降级：仓库缺席显式跳过；落库失败仅告警不阻塞启动（业务门本就 fail-closed 兜底）；
- 开关：`app.biz-order.seed.enabled`（缺省 true；真订单系统接入后置 false）。

### G3. 防复发

`BizOrderSeedRunnerTest.seedRows_alignedWithOrderQueryServiceMock` 把「种子行与 mock 的
orderId/userId/amount/状态映射一致」钉成测试——今后改 mock 不同步种子会直接测试失败。

### G4. 验证

`mvn clean test` 全量 **1247 例 0 失败 0 错误**（+5 种子用例）。**运行中的实例需重启**（种子在
启动期灌入）；H2 内存库每次启动重灌、MySQL 持久库仅首次补数。

## 附记 H：管理台工单"决议后消失"修复（2026-09-20）

### H1. 现象与根因

种子修复（附记 G）后，用户【确认执行】成功 → 工单流转 APPROVED → 刷新 /admin 人工审核页，
列表为空（"又没有对应工单"）。根因：`GET /admin/hitl/tickets` 只返回 **PENDING** 工单，而提交制下
决议是事件、**工单是唯一审计留痕**——决议成功反而让它从唯一可见处退场（前端状态徽章/`resolvedAt`
展示本已就绪，是后端不给数据）。叠加因素：内嵌库/内存态工单重启即清，旧单不会回来自动补位。

### H2. 修复：全量工单留痕视图

- `HumanTicketService.allTickets()`：内存 + DB 持久副本合并（重启不丢历史），排序 PENDING 优先、
  其余按 createdAt 倒序；`pendingTickets()` 保留原语义（可观测计数仍用 PENDING 口径）；
- `AdminHitlController.tickets()` 改用全量视图——已决议单带状态徽章（已确认/已驳回/已超时）继续
  可见，操作按钮仅 PENDING 渲染（前端本就按状态分支，零逻辑改动，仅空态文案同步）；
- 回归钉：`tickets_afterConfirm_ticketStaysListed_withApprovedBadge`——确认成功后刷新列表，
  工单以 APPROVED 留痕而非消失。

### H3. 验证

`mvn clean test` 全量 **1248 例 0 失败 0 错误**（+2 用例）。注意：内嵌库重启会清空工单历史
（DB 持久副本仅在 MySQL 等持久库生效）；重启后需重新发起一次售后请求生成新工单。
