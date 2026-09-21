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
| [rag-corpus-ingestion-tutorial.md](guides/rag-corpus-ingestion-tutorial.md) | 多格式语料「清洗-切分-入库」教程（10 类文档分型/幂等/嵌入缓存/坑；配套 [scripts/rag-ingest](../../scripts/rag-ingest/README.md)） |

## reports/ — 交付报告与实验记录

| 文档 | 内容 |
|------|------|
| [2026-09-19-refusal-kb-ingest-report.md](reports/2026-09-19-refusal-kb-ingest-report.md) | 拒答机制 + 知识库录入交付报告（含「全绿≠无缺陷」缺陷清单） |
| [rag-redteam-conflict-demo.md](reports/rag-redteam-conflict-demo.md) | RAG 红队演示：毒片实验与防线盲区诚实标注（配套 eval [rag-redteam.json](../../src/main/resources/eval/rag-redteam.json)） |

## blog/ — 对外技术博客（中英双语）

| 文档 | 内容 |
|------|------|
| [2026-09-16-agent-latency-stability-tuning.zh.md](blog/2026-09-16-agent-latency-stability-tuning.zh.md)（[EN](blog/2026-09-16-agent-latency-stability-tuning.en.md)） | 全链路延迟 80.5s → 6.1s 实录：超时预算治理、SSE 生命周期、意图漂移修复 |
| [2026-09-18-decision-arbiter-hitl-l2-access-gate.zh.md](blog/2026-09-18-decision-arbiter-hitl-l2-access-gate.zh.md) | 生产化硬化实录：会话仲裁器、HITL L2 持久化、统一访问闸口 |

## 其他

| 位置 | 内容 |
|------|------|
| [sql/](sql/) | HITL L2 三表 DDL + mock 数据（用户自建，不入库跟踪） |
| [../scripts/rag-ingest/](../scripts/rag-ingest/README.md) | Python 批量入库流水线 |
| [../src/main/resources/corpus/](../src/main/resources/corpus/) | RAG 知识库语料（运行时数据，非文档） |
