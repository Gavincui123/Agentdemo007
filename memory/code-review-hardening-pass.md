---
name: code-review-hardening-pass
description: 2026-09-08 codereview 修 6 处：CircuitBreaker 加 synchronized（Phase17 暴露并发）/RewriteQualityChecker 回退重置 queryEnrichment/eval golden 端到端守卫/ToolExecutionStep metrics 包 try-catch/QueryEnricher 排金额+ASCII 大写/InMemoryVectorStore.searchByKeywords 大小写无关（604 测）
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-08T00:40:03.129Z
---

2026-09-08 对 Phase 17+20 已写代码做一遍 codereview，TDD 修 6 处（每条先 RED 看失败再最小 GREEN），全量 594→604 测零回归。

**① CircuitBreaker 并发（高）**：Phase 5 `CircuitBreaker`（resilience/）原单线程单测设计（非 volatile 字段 + 非原子 `++consecutiveFailures`），Phase 17 `ToolCircuitBreaker` 把它按 toolName 装入 `ConcurrentHashMap` 暴露给 Spring 多请求并发 → 丢失更新致熔断该开不开。修：`allowRequest`/`recordSuccess`/`recordFailure`/`state` 四方法加 `synchronized`。`CircuitBreakerConcurrencyTest`（64 线程×100 barrier 压测）证 RED→GREEN。

**② RewriteQualityChecker 补全槽残留（中）**：质量校验回退 `standardQuery` 时漏重置 `queryEnrichment` → 槽残留坏改写抽出的词，与回退后 standardQuery 不一致，误导 ③环节测评（检索链路 HybridRetriever 自行 enrich 不读此槽，故检索不受影响）。修：注入 `QueryEnricher` + 回退时 `setQueryEnrichment(enricher.enrich(rawInput))`（保留无参构造旧测试零改动）。

**③ Phase 20 eval 端到端守卫（中）**：T92 把 `hasFragments`/`ragFragmentsContain`/`queryEnrichmentContains` 改真对照后，`GoldenSuiteTest` 的 trivial 执行器无法满足 → rag/understanding 两 stage 冒烟**全红但非回归**（该测只断 stage 名+totalCases，不断 totalPassed，故不破坏构建）。更要紧：Phase 20 golden 期望值从未对真实流水线验证过（EvalExecutorPhase20Test 用 fake 执行器只测对照逻辑）。修：补 `EvalGoldenPhase20IntegrationTest`（@SpringBootTest，真实 QueryEnricher+RagStep+RagSeedRunner 种子）钉死 rag-006/007/und-006/007 期望值——dev 确定性可过（characterization 守卫，GoldenSuiteTest 先例允许立即通过）。**注意**：改 QueryEnricher 正则/HybridRetriever/RagSeedRunner 种子时，此集成测试会守卫回归。

**④ ToolExecutionStep metrics 反噬（低）**：降级 catch 内调 `metrics.recordToolCircuitOpen` 后才 `return ShortCircuit`；Micrometer 对非法 tag 会抛 → 反噬降级。修：两 catch 内 metrics 调用包 `try/catch` 吞掉（仅记 warn）。`ToolExecutionStepMetricSafetyTest` 注入抛异常的 metrics 替身证 RED→GREEN。

**⑤ QueryEnricher 纯数字误判（低）**：`ID_TERM=[A-Za-z0-9]{4,}` 把金额（"消费满10000元"的 10000）误判关键词。**注意**：既有 `pureDigitOrderId_extractedAsKeyword` 测试**故意**抽取纯数字订单号——不能"要求含字母"。修：只排除**金额上下文**的纯数字 token（前接 满/达/约/超 或 消费/金额/超过；后接 元/万/块/亿），含字母标识符 + 无金额上下文的纯数字 ID 仍抽。

**⑥ 关键词通道大小写（低，dev 限定）**：`InMemoryVectorStore.searchByKeywords` 的 `text.contains(kw)` + `HashEmbeddingService.tokenize` 均大小写敏感 → 小写订单号零召回。修：QueryEnricher ASCII 关键词 `toUpperCase` 规范化 + `searchByKeywords` 改 `text.toLowerCase().contains(kw.toLowerCase())`。向量通道哈希嵌入仍大小写敏感（改 tokenize 风险大，prod 真实 embedding 不区分，留 dev 限制）。

相关：[[phase17-18-plan]]、[[phase20-retrieval-enhancement-plan]]、[[degradation-and-eval-principles]]。
