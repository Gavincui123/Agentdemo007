# 前端可视化界面设计规格

> 日期：2026-09-03
> 关联：`docs/DEVELOPMENT-PLAN.md`（后端七层架构）、`docs/框架文件.md`
> 状态：已通过 brainstorm 评审，待纳入开发计划

## 1. 目标
为 Agentdemo007 后端平台提供完整可视化前端，让七层架构能力可交互、可观测、可运维。覆盖对话、运维管理、评测、可观测性四大模块，与后端「完整对齐」。

## 2. 范围
- 对话界面（让 `/chat` 可用）
- 运维管理台（路由权重调整、模型配置查看、HITL 工单、会话历史）
- 评测可视化（`/eval/run` 报告）
- 可观测性仪表盘（指标卡片、ECharts 时序图、链路检索、告警列表）

## 3. 技术栈（已确认）
| 项 | 选型 |
|---|---|
| 框架 | Vue 3 + Vite + TypeScript |
| UI 库 | Element Plus |
| 图表 | ECharts |
| 状态 | Pinia |
| 路由 | Vue Router |
| HTTP | axios |
| 流式 | EventSource（SSE）封装 |
| Markdown | markdown-it |
| 测试 | Vitest（+ 可选 Playwright E2E） |

## 4. 部署方式（已确认）
- 开发：`vite dev`(:5173) `proxy → backend :8080`，CORS 由代理解决。
- 构建：`vite build → dist/`。
- 生产：`dist/` 拷入 Spring Boot `src/main/resources/static`（单 jar 部署，推荐）；或 nginx 托管反代后端。

## 5. 工程结构
```
frontend/
├── package.json / vite.config.ts / tsconfig.json / index.html
└── src/
    ├── main.ts / App.vue
    ├── router/index.ts            # Vue Router（4 模块路由 + 守卫）
    ├── api/
    │   ├── http.ts                # axios 实例 + 拦截器（traceId 透传/统一错误）
    │   ├── chat.ts                # /chat 同步 + SSE
    │   ├── eval.ts                # /eval/run
    │   ├── admin.ts               # 路由权重 / 模型配置 / HITL 工单 / 会话历史
    │   └── obs.ts                 # 指标 / 链路聚合
    ├── views/
    │   ├── chat/                  # 对话界面
    │   ├── admin/                 # 运维管理台
    │   ├── eval/                  # 评测可视化
    │   └── observability/         # 可观测性仪表盘
    ├── components/                # 通用组件（MessageBubble/JsonViewer/…）
    ├── stores/                    # Pinia（会话/全局状态）
    └── utils/sse.ts               # EventSource 封装（断线重连）
```

## 6. 页面设计

### 6.1 对话界面（Phase 16，对应后端 `/chat`）
- 用户/AI 气泡 + Markdown 渲染。
- SSE 流式逐字追加输出。
- sessionId 维持多轮上下文。
- 折叠面板展示：识别意图、模型路由标签、工具调用参数/结果、RAG 检索片段。
- 故障转移/429 降级话术以系统提示样式区分。

### 6.2 运维管理台（Phase 17，对应 `RouteWeightController` + 模型配置中心）
- 路由权重动态调整（滑块 → 写回配置中心，热生效）。
- 模型元数据表（端点/上下文窗口/流控/容灾策略）。
- HITL 工单列表 + 确认/驳回。
- 会话历史检索查看。

### 6.3 评测可视化（Phase 17，对应 `/eval/run`）
- 用例批量输入 / JSON 导入。
- 报告卡片 + 表格：准确率/召回率/路由正确率/工具正确率/RAG 命中率/故障转移率/P95 耗时/Token。
- 失败用例明细 + 对比。

### 6.4 可观测性仪表盘（Phase 17，对应 actuator/OTel）
- 指标卡片（QPS/P95 延迟/错误率/Token 消耗/熔断状态）。
- ECharts 时序折线图。
- traceId 检索 → 节点瀑布图。
- 告警列表。
- 链路拓扑/详细图表若后端已接 Grafana/Jaeger，可改 iframe 嵌入（后续定，不影响主结构）。

## 7. 数据流
| 场景 | 方式 | 说明 |
|---|---|---|
| 同步 /chat | axios POST | UnifiedResponse |
| 流式 /chat | EventSource SSE | 逐 token 追加，首帧携带 traceId |
| 评测 / 管理 | axios GET/POST | 路由权重、模型配置、工单 |
| 可观测性 | 轮询 `GET /api/obs/summary` | 后端新增轻量聚合端点返回 JSON 指标快照，避免浏览器解析 Prometheus 文本；链路详情用 traceId 调后端检索 |

## 8. 关键技术点
- **traceId 透传**：axios 拦截器读响应头 `X-Trace-Id`，与后端日志/链路对齐；SSE 首帧携带 traceId。
- **SSE 封装**：`utils/sse.ts` 统一处理 `data:` 事件、错误、断线重连、关闭。
- **错误处理**：503（Redis 故障）→ 降级提示；429 → 限流话术；注入拦截 → 系统气泡。
- **安全**：管理台/评测页前端路由守卫 + 后端鉴权双重。

## 9. 错误处理
- 统一 axios 拦截器：错误码 → Element Plus Message。
- SSE 断线自动重连，重连失败提示。
- 503/429/注入拦截各有对应 UI 状态。

## 10. 测试
- Vitest：stores / utils（SSE 解析、错误映射）单测。
- 可选 Playwright E2E：对话流式 + 管理台调权冒烟。

## 11. Phase 划分（卡后端关键节点）
- **Phase 16 — 前端工程骨架 + 对话界面**（紧跟 Phase 12 `/chat` 全链路打通后）：Vite 工程、路由、axios/SSE 封装、对话页（流式 + 工具/RAG 折叠展示）。可先用 mock 提前开工。
- **Phase 17 — 管理台 + 评测可视化 + 可观测性仪表盘**（紧跟 Phase 15 后端管理/评测/可观测完成后）：三大页面模块，对接真实后端接口。

## 12. 后端配套
- 新增 `GET /api/obs/summary` 轻量聚合端点（Phase 17 前端依赖，后端在 Phase 15 或 Phase 17 补）。
- Spring Boot 默认托管 `src/main/resources/static`，支持前端单 jar 部署。
- 开发期 CORS 由 Vite proxy 解决，无需后端开 CORS。
