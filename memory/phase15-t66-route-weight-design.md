---
name: phase15-t66-route-weight-design
description: Phase 15 T66 运维控制台调权：ConfigWriter seam + ModelConfigCenter.applyWeight 内存热生效 + best-effort 写回（②降级）
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-07T10:50:23.689Z
---

Phase 15 T66（动态调权热生效，520 测试）：

**ConfigWriter seam**（`com.agentdemo007.config`，§5.14 引擎无关内核）：
- `@FunctionalInterface boolean writeModelWeight(String modelId, int weight)`，domain-typed（不暴露 YAML/内容格式）。
- `NO_OP = (modelId, weight) -> false`（dev 占位，不持久化）。
- prod `NacosConfigWriter` 后接（`@ConditionalOnMissingBean` 覆盖，调 `ConfigService.publishConfig` → Nacos 监听 → 各实例 `ModelConfigCenter.refresh()` 收敛）。形态对齐 [[phase8-context-design]] 的 TraceContextPropagator/MessagePublisher：seam 在前，dev 占位，prod 后接。

**ModelConfigCenter.applyWeight(modelId, weight)**（热生效即时路径）：
- `synchronized`；`registry.get(id)` → `ModelMetadata.withWeight(newWeight)` 重建不可变副本 → `registry.register`（同 id 覆盖）→ true。
- 未知模型 / 空id / 负权重 → false（②降级不抛）。
- 仅改本进程内存注册表；dev 无写回时，后续 `refresh()` 会从源还原（预期语义：dev 热生效仅在两次刷新间成立）。

**ModelMetadata.withWeight(int)**：`new ModelMetadata(...)` 复制其余字段（不可变 → 整体替换而非原地改）。

**RouteWeightController**（`com.agentdemo007.admin`，`POST /admin/route-weights`）：
- `@RequestBody RouteWeightRequest(modelId, weight)` record；校验空id/负权重 → 400 BAD_REQUEST。
- `applyWeight` 未命中 → 404 NOT_FOUND。
- 命中 → `writeBackBestEffort`（try/catch 吞异常，②降级：写回失败不回滚内存、不抛 5xx）→ `UnifiedResponse.success(RouteWeightResponse(modelId, weight, applied, persisted))`。
- 强类型 request/response（非 Map，④收口）。鉴权留 T72 收口（`/admin/*` 应受保护）。

**AdminConfig**：`@Bean @ConditionalOnMissingBean(ConfigWriter.class) ConfigWriter configWriter()` = NO_OP（dev）。

**Why**：满足验收"运维控制台调权后路由权重热生效"——内存 applyWeight 即时可见，写回 seam 为 prod Nacos 持久化/传播预留。
**How to apply**：同类"运维动态调参 + 热生效"复用此模式（domain-typed seam + NO_OP + 内存即时应用 + best-effort 写回）。
