# Agentdemo007 工程实录 · 系列目录

一个电商客服 Agent 从「能跑」到「敢挂公网当作品集」的全过程实录。每一章聚焦一个模块，统一按「问题 → 根因 → 抉择 → 落地」展开：所有数字来自真实联调日志与全量回归，所有代码摘自仓库原文，所有踩过的坑和没做的欠账如实列出。

**系列主线**：**设计篇**（第一~二章：架构与流水线宪法、提示词体系）→ **攻坚篇**（第三~九章：韧性网关、会话记忆、检索、决策与闸门、业务工作流、全链路调优、评测）→ **交付篇**（第十~十二章：前端体验、可观测、部署公网）。章节按依赖与生命周期编排，与成文日期无关。

**项目源码**：[github.com/Gavincui123/Agentdemo007](https://github.com/Gavincui123/Agentdemo007)

### 设计篇

| 章节 | 文档 | 一句话 |
|---|---|---|
| 第一章 | [设计理念与总体架构](https://blog.csdn.net/qq_24993561/article/details/166257744) | 七层蓝图、15 步流水线、四态出口与选型答辩——「外部依赖缺失时能力降级而非崩溃」 |
| 第二章 | [提示词工程体系](https://blog.csdn.net/qq_24993561/article/details/166257780) | 分段装配、防漂移三事故、注入位纪律与双源热更的欠账 |

### 攻坚篇

| 章节 | 文档 | 一句话 |
|---|---|---|
| 第三章 | [模型网关与韧性治理](https://blog.csdn.net/qq_24993561/article/details/166257763) | 异常分诊四层、重试归属地、熔断三态与阈值光谱、per-tool 熔断与自纠正轮次耗尽如实收口 |
| 第四章 | [会话记忆分层：L1/L2/L3 与三轮加固](https://blog.csdn.net/qq_24993561/article/details/166257790) | token 预算窗口 + 滚动摘要 + 用户画像；一次 review 揪出注入位、读-改-写竞态、首字延迟敞口三个隐患 |
| 第五章 | [检索侧演进：Hybrid、时效、知识分级与拒答](https://blog.csdn.net/qq_24993561/article/details/166257786) | 双通道融合与稀疏优先置顶、时效隔离标注、客户等级 ABAC 三轴拆分、拒答四层与红队 |
| 第六章 | [决策层、持久化与闸门硬化](https://blog.csdn.net/qq_24993561/article/details/166257730) | 会话仲裁器三层递进、HITL 检查点三级持久化、统一访问闸口、真 RAG 收尾与毒性实验 |
| 第七章 | [业务工具与售后工作流 DAG](https://blog.csdn.net/qq_24993561/article/details/166257733) | 有界 Agent loop、工具断路器与自纠正、订单归属校验、mock 与 DB 对账、审批 = 事件 |
| 第八章 | [全链路延迟与稳定性调优（80.5s → 6.1s）](https://blog.csdn.net/qq_24993561/article/details/166257713) | 超时预算成链路地算、SSE 生命周期善后、意图漂移与话题缠绕修复、兜底人格一致性 |
| 第九章 | [评测体系：给评测做评测](https://blog.csdn.net/qq_24993561/article/details/166257751) | hollow eval 事故（断言字段被静默丢弃）、评测异步化、黄金集 Nacos 双源、「全绿 ≠ 无缺陷」 |

### 交付篇

| 章节 | 文档 | 一句话 |
|---|---|---|
| 第十章 | [前端与流式交互](https://blog.csdn.net/qq_24993561/article/details/166257757) | 单 jar 里的 SPA、手撸 SSE 协议与测试钉死、权威终态收口、三重前端闸口 |
| 第十一章 | [可观测性与审计](https://blog.csdn.net/qq_24993561/article/details/166257770) | trace / audit / progress 三条正交信道、append-only 审计链、「空白即线索」 |
| 第十二章 | [部署交付实录](https://blog.csdn.net/qq_24993561/article/details/166257739) | 一进程即整站、九个环境语义错位的翻车、公网安全清单、回滚即换指针 |

**番外（非系列叙事，工具与操作手册）**：[语料清洗切分入库教程](https://github.com/Gavincui123/Agentdemo007/blob/master/docs/guides/rag-corpus-ingestion-tutorial.md) · [部署实录：15+ 个真实问题的现象→根因→修复](https://github.com/Gavincui123/Agentdemo007/blob/master/docs/guides/%E9%83%A8%E7%BD%B2%E5%AE%9E%E5%BD%95.md) · [红队演示手册](https://github.com/Gavincui123/Agentdemo007/blob/master/docs/reports/rag-redteam-conflict-demo.md)

> 正文配图引用 `assets/` 下的 PNG（兼容 CSDN 等不支持 SVG 的平台）；同名 SVG 为矢量源文件保留在同目录，GitHub 仓库内可直接渲染。多数章节另附 mermaid 图，由 GitHub 原生渲染。
