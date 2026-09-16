---
name: nacos-prompt-registry-fit
description: Nacos 3.x Prompt 管理适配评估——契合度高，nacos-client 2.2.3→3.x 是前置卡点
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-04T02:54:35.741Z
---

Nacos 3.x 的 Prompt 管理功能（集中维护提示词模板：模板内容+变量定义+版本号+标签+描述，资源标识 `namespaceId→prompt→promptKey`，按版本或按标签读取，支持 md5 缓存；发布走草稿→审核→发布→更新 latest 标签，可启 Pipeline）与本项目契合度高。评估基于官方概述页原文（用户 2026-09-04 提供）。

**契合点（映射到现有代码）：**
- 项目 `LlmRequest(modelId, prompt, maxTokens)` 的 prompt 是裸 String，无任何模板/版本/变量设施——prompt-registry 正好填这个空白。
- 两种读取模式天然映射环境：按版本→dev/eval（确定性，配合 [[degradation-and-eval-principles]] 的环节测评，golden eval 不被 Nacos 热改破坏）；按 latest 标签→prod（免发布跟随）。
- 落点应新建 `PromptRegistry` 概念（按 scene/model 取模板+变量渲染），dev 本地源/降级回退（同 `ModelConfigSource` 的 `@ConditionalOnMissingBean` 套路），prod Nacos 源。属 [[phase4-gateway-design]] 配置中心家族新成员。

**三个不要混淆的 Nacos 落点：**
1. 模型配置 `ModelConfigSnapshot`（模型表/路由/流控/容灾）→ Nacos config dataId（已设计，impl deferred）。
2. 提示词模板（系统/场景 prompt，送 LLM）→ Nacos prompt registry（本次新增）。
3. 降级话术 `DegradationPhraseCenter`（面向用户的兜底话术，不送 LLM）→ 独立，非 prompt，可另存 config 但语义不同。

**前置卡点（待"查询 Prompt"客户端 API 参考页确认）：** 文档确认有 typed Client API（"应用可通过 Client API 查询 Prompt"）。prompt-registry 是 3.x 能力，pom 现为 `nacos-client:2.2.3`（2.x），typed API 大概率不在 2.x。二选一：升 client 到 3.x，或绕开 SDK 直连 HTTP REST。服务器是 `nacos/nacos-server:latest`(3.x，standalone，MySQL 持久化 nacos_config 库)，服务端支持。需"查询 Prompt"页给出方法签名/HTTP 路径/变量渲染语法（`{{}}` vs `${}`）才能定方案。

**鉴权：** docker-compose 已配 `NACOS_AUTH_TOKEN` + `NACOS_AUTH_IDENTITY_KEY/VALUE`，拉 prompt 要带。配置接入走自研 SPI `NacosEnvironmentPostProcessor`（非 spring-cloud），dev 空地址优雅跳过。
