# 文档导航（Agentdemo007）

> 本目录按「架构 → 设计 → 指南 → 报告 → 博客」分类管理；本文件是唯一导航入口，根 [README](../README.md) 的文档地图指向这里。
> 新增文档请按下方分类落位，并在本索引登记一行。

## 总计划

| 文档 | 内容 |
|------|------|
| [DEVELOPMENT-PLAN.md](DEVELOPMENT-PLAN.md) | 22 个 Phase 的工程化开发计划与实现注记（Phase 22 = 会话记忆分层 L1 窗口 / L2 滚动摘要 / L3 用户画像；每处与原设计偏差的诚实记录） |

## architecture/ — 架构与全景

| 文档 | 内容 |
|------|------|
| [项目全景详解.md](architecture/项目全景详解.md) | **从这开始读**：15 章全景——架构、流水线步骤、意图识别/多轮对话踩坑实录（漂移/缠绕/粘性/模糊澄清/仲裁器）、RAG 漏斗、HITL L2、闸口、关键数字速查 |
| [框架文件.md](architecture/框架文件.md) | 七层架构总图 |

## design/ — 专项设计规格（specs）与 TDD 实现计划（plans）

### specs/（设计裁决：为什么这样设计）

| 文档 | 内容 |
|------|------|
| [2026-09-03-frontend-design.md](design/specs/2026-09-03-frontend-design.md) | 前端设计规格 |
| [2026-09-14-p0-intent-switch-clarify-design.md](design/specs/2026-09-14-p0-intent-switch-clarify-design.md) | P0 意图切换 + 模糊澄清设计（§7.5 并发合并） |
| [2026-09-14-segmented-systemprompt-intent-design.md](design/specs/2026-09-14-segmented-systemprompt-intent-design.md) | 分段式系统提示词（意图驱动装配）设计 |

### plans/（实现计划：怎么一步步落地）

| 文档 | 内容 |
|------|------|
| [business-tools-workflow-dag.md](design/plans/business-tools-workflow-dag.md) | 业务工具/售后工作流 DAG 实现计划 |
| [per-intent-dag.md](design/plans/per-intent-dag.md) | 按意图分型 DAG 实现计划 |
| [2026-09-14-p0-intent-switch-clarify.md](design/plans/2026-09-14-p0-intent-switch-clarify.md) | P0 意图切换 TDD 实现计划 |
| [2026-09-14-segmented-systemprompt-assembler.md](design/plans/2026-09-14-segmented-systemprompt-assembler.md) | 分段提示词装配器 TDD 实现计划 |

## guides/ — 实操指南

| 文档 | 内容 |
|------|------|
| [部署实录.md](guides/部署实录.md) | 首次上线的踩坑手册：profile/dataId/容器网络/MySQL 授权/nginx 接入等 15+ 个真实问题的现象→根因→修复，含更新回滚流程与安全清单（标准手册见根 [DEPLOY.md](../DEPLOY.md)） |
| [rag-corpus-ingestion-tutorial.md](guides/rag-corpus-ingestion-tutorial.md) | 多格式语料「清洗-切分-入库」教程（10 类文档分型/幂等/嵌入缓存/坑；配套 [scripts/rag-ingest](../scripts/rag-ingest/README.md)） |

## reports/ — 交付报告与实验记录

| 文档 | 内容 |
|------|------|
| [2026-09-19-refusal-kb-ingest-report.md](reports/2026-09-19-refusal-kb-ingest-report.md) | 拒答机制 + 知识库录入交付报告（含「全绿≠无缺陷」缺陷清单） |
| [rag-redteam-conflict-demo.md](reports/rag-redteam-conflict-demo.md) | RAG 红队演示：毒片实验与防线盲区诚实标注（配套 eval [rag-redteam.json](../src/main/resources/eval/rag-redteam.json)） |

## blog/ — 对外技术博客（系列：《Agentdemo007 工程实录》，[系列目录](blog/README.md)）

| 文档 | 内容 |
|------|------|
| [系列目录](blog/README.md) | 十二章工程实录总览（设计篇 → 攻坚篇 → 交付篇）：架构与提示词 / 韧性·记忆·检索·决策·工具·调优·评测 / 前端·可观测·部署 |
| [2026-09-21-design-philosophy-architecture.zh.md](blog/2026-09-21-design-philosophy-architecture.zh.md) | 第一章·设计理念与总体架构：七层蓝图、15 步流水线、四态出口、选型答辩 |
| [2026-09-21-prompt-engineering-system.zh.md](blog/2026-09-21-prompt-engineering-system.zh.md) | 第二章·提示词工程体系：分段装配、防漂移、注入位纪律、双源热更 |
| [2026-09-21-model-gateway-resilience.zh.md](blog/2026-09-21-model-gateway-resilience.zh.md) | 第三章·模型网关与韧性治理：异常分诊四层、重试归属地、熔断阈值光谱、有界 Agent loop |
| [2026-09-21-session-memory-layering.zh.md](blog/2026-09-21-session-memory-layering.zh.md) | 第四章·会话记忆分层：L1/L2/L3、读-改-写竞态收口、画像注入位隔离与读路径三道防线 |
| [2026-09-21-rag-evolution-abac-refusal.zh.md](blog/2026-09-21-rag-evolution-abac-refusal.zh.md) | 第五章·检索侧演进：Hybrid 融合、时效治理、知识分级 ABAC、拒答四层与红队 |
| [2026-09-18-decision-arbiter-hitl-l2-access-gate.zh.md](blog/2026-09-18-decision-arbiter-hitl-l2-access-gate.zh.md) | 第六章·决策层、持久化与闸门硬化：会话仲裁器、HITL L2 持久化、统一访问闸口 |
| [2026-09-21-business-tools-workflow-dag.zh.md](blog/2026-09-21-business-tools-workflow-dag.zh.md) | 第七章·业务工具与售后工作流 DAG：方向纠偏、对账对齐、审批=事件、建单幂等四态 |
| [2026-09-16-agent-latency-stability-tuning.zh.md](blog/2026-09-16-agent-latency-stability-tuning.zh.md)（[EN](blog/2026-09-16-agent-latency-stability-tuning.en.md)） | 第八章·全链路延迟 80.5s → 6.1s 实录：超时预算治理、SSE 生命周期、意图漂移修复 |
| [2026-09-21-eval-harness-hollow-eval.zh.md](blog/2026-09-21-eval-harness-hollow-eval.zh.md) | 第九章·评测体系：hollow eval 事故、异步化、黄金集双源、「全绿≠无缺陷」 |
| [2026-09-21-frontend-streaming-ux.zh.md](blog/2026-09-21-frontend-streaming-ux.zh.md) | 第十章·前端与流式交互：单 jar 里的 SPA、手撸 SSE 协议、权威终态收口 |
| [2026-09-21-observability-audit-trace.zh.md](blog/2026-09-21-observability-audit-trace.zh.md) | 第十一章·可观测性与审计：三条正交信道、append-only 审计链、「空白即线索」 |
| [2026-09-21-deploy-delivery-hardening.zh.md](blog/2026-09-21-deploy-delivery-hardening.zh.md) | 第十二章·部署交付实录：一进程即整站、环境语义错位九连、公网安全清单 |

## 其他

| 位置 | 内容 |
|------|------|
| [../src/main/resources/sql/](../src/main/resources/sql/) | HITL L2 三表 DDL + mock 数据（用户自建，不入库跟踪） |
| [../scripts/rag-ingest/](../scripts/rag-ingest/README.md) | Python 批量入库流水线 |
| [../src/main/resources/corpus/](../src/main/resources/corpus/) | RAG 知识库语料（运行时数据，非文档） |
