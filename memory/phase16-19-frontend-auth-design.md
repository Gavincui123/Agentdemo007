---
name: phase16-19-frontend-auth-design
description: Phase 16/19 前端全栈 + 单jar鉴权：vue-tsc -b 才是真类型检查/拦截器收口鉴权/vite直出static
metadata: 
  node_type: memory
  type: project
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-08T14:31:29.996Z
---

Phase 16（对话）+ Phase 19（管理台/评测/可观测）**已实现**全栈：

**前端栈**：Vue3+Vite8(rolldown)+TS6+Element Plus2.9(深色靛蓝/磷光青/琥珀主题)+ECharts5.6+Pinia+vue-router hash模式。54 测 GREEN。

**单 jar 部署**：vite `build.outDir: '../src/main/resources/static'` + `emptyOutDir:true`（非 frontend-maven-plugin，最简）。**hash 路由故无需 SPA fallback**——浏览器只请求 `/`，API 路径 `/chat /admin/* /eval/run /api/obs/*` 与之零冲突。部署流：`npm run build && mvn clean package`（**必须 clean**）。已验 jar 内含 `BOOT-INF/classes/static/index.html` + assets。`.gitignore` 加 `src/main/resources/static/` + `frontend/node_modules/`。
**坑**：`mvn package` 不带 clean 会**累积死 chunk**——maven-resources-plugin 只复制 src→target/classes 不删除孤儿文件，而 vite content-hash 每次构建都改 chunk 名，故每次非 clean 重打包都把上一轮的旧 hash chunk（ObservabilityView ~1MB/个）留在 jar 里（24 chunk 含 stale vs clean 15 chunk）。`emptyOutDir:true` 只清 src/main/resources/static/（已验 on-disk 无累积），target/classes 须靠 `mvn clean`。**部署/CI 一律 `mvn clean package`**。

**鉴权收口（T103）**：后端**无** spring-security 依赖（沿用 seam 模式）。`AdminAuthenticator`(@FunctionalInterface seam) + `AdminSecurityConfig`(@ConditionalOnMissingBean 默认 `agentdemo.admin.token`=dev-admin-token) + `AdminAuthInterceptor`(@Component HandlerInterceptor) + `AdminWebConfig`(注册 `/admin/**` `/api/obs/**`)。**路径级拦截器而非每控制器注入**——4 个管理台控制器零改动、各自直构单测不受影响（直构绕过拦截器）。镜像 `EvalAuthenticator`(eval token) 模式但令牌不同（admin vs eval）。①话术短路：未授权→`UnifiedResponse.error(UNAUTHORIZED)` code=401 **HTTP 200** 体内表达，写体同构 `InputSecurityFilter`(setStatus200+application/json+UTF-8)。TraceId 在拦截器时已由 TraceFilter(HIGHEST_PRECEDENCE) 写 MDC。3 测 GREEN，全量 **624 测 GREEN**（新 bean 上下文加载无误）。

**前端鉴权**：`auth.ts`(localStorage + 纯函数 `attachAdminToken(config)`) + `http.ts` 请求拦截器注入 `X-Admin-Token` + `AuthView.vue` 令牌录入 + `router.beforeEach` 守卫 `/admin` `/obs`（无令牌→`/auth?redirect=`）。话术短路：前端不校验令牌正确性，原样出站后端短路 401。

**关键 gotcha**：`vue-tsc --noEmit` **不充分**——它读 tsconfig.json 而非 app config；**`vue-tsc -b`（即 `npm run build`）才是真类型检查**，会抓 `verbatimModuleSyntax` 下 `import type {ApiError}` 用于 `instanceof`(TS1361) 与 axios `InternalAxiosRequestConfig` cast 不重叠(TS2352，需 `as unknown as`)。以后一律用 `npm run build`/`vue-tsc -b` 验类型。

**已知限制**：ObservabilityView 拉 full echarts → chunk ~1MB（lazy 仅 /obs 加载），未来可改 echarts 按需 import 瘦身。admin/obs 控制器 javadoc 仍写"此处先开放便于联调"——指控制器层开放（鉴权在拦截器），非过时。

**部署指南**：根目录 `DEPLOY.md` 是权威部署文档（架构/前置/dev/生产/完整环境变量表/外部依赖矩阵/数据库初始化/Actuator/日志/已知限制/容器化示例/速查）。
**prod 建表已闭环**：`src/main/resources/schema.sql`（`CREATE TABLE IF NOT EXISTS chat_turn/audit_event`，逐字段对齐 @Column，类型按 Hibernate6 MySQL 方言默认：BIT(1)/TIMESTAMP(6)/TEXT/VARCHAR/BIGINT）+ `application-prod.yml` 的 `spring.sql.init.mode=always`（prod MySQL 非内嵌库才执行；dev H2 走 ddl-auto=update，schema.sql IF NOT EXISTS 跳过零冲突）。无 Flyway/Liquibase，结构演进改 schema.sql（仅幂等语句）。`SchemaSqlInitializationTest`(4 测，properties 强制 ddl=none+sql.init=always) 钉死 schema.sql 路径：字段漂移/语法拒收→save 抛红。全量 628 GREEN。
部署坑：部署/CI 一律 `mvn clean package`（死 chunk 累积，见上）。
