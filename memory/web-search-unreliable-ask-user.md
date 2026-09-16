---
name: web-search-unreliable-ask-user
description: 本环境 WebFetch/WebSearch/Bash分类器不可靠；需权威外部材料(Maven坐标/官方文档)时请用户代为搜集粘贴
metadata: 
  node_type: memory
  type: feedback
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-10T13:37:18.177Z
---

本环境 WebFetch（Maven 仓库目录 403、search.maven.org API 超时）+ WebSearch（空结果）+ Bash 分类器（间歇限流，连 -U/compile 都跑不了）都不可靠，查不到权威外部材料。

**Why:** 多次查 LC4j Maven 坐标失败，反而让 AI 生成 doc 的幻觉版本（1.20.0-beta30）混入记忆；用户 2026-09-10 明确："你的搜索不能行，下次需要搜索什么材料请告诉我，我搜集到后会将原文复制给你"——随后用户从 mvnrepository 代查钉了真实坐标（langchain4j-core 1.19.0 GA / langchain4j-open-ai-spring-boot4-starter 1.19.0-beta29，无 BOM），纠正了幻觉。

**How to apply:** 需要权威外部材料（Maven/NPM 坐标、官方文档原文、版本兼容矩阵）时，**直接告诉用户要查什么 + 去哪查（mvnrepository.com / 官方 doc URL）**，请用户代为搜集粘贴原文，不要自己反复试 WebFetch/WebSearch（浪费回合 + 易引入幻觉坐标）。自己只信用户粘贴的原文 + 代码库内证据。tavily-cli（tvly 经 Bash）也可能被分类器挡。

关联 [[langchain4j-boot4-compat-findings]]、[[routeplan-design]]。
