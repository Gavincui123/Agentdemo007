---
name: nacos-config-mechanism
description: Phase 2 Nacos 配置接入实际机制（nacos-client + EnvironmentPostProcessor + NacosConfigRefresher 活源，非 spring.config.import / @RefreshScope）
metadata:
  type: project
  originSessionId: code-review-2026-09-05
  modified: 2026-09-09T02:45:42.970Z
---

Phase 2 Nacos 配置中心接入**不走**计划原始描述的 `spring.config.import: nacos:` + `@RefreshScope`，而是走降级路径（计划 §8 风险表允许）：

- **依赖**：裸 `nacos-client` 3.2.4（**不引** `spring-cloud-starter-alibaba-nacos-config` / `spring-cloud-context`——与 Boot 4.1 兼容风险高）。
- **启动加载**：`NacosEnvironmentPostProcessor`（`EnvironmentPostProcessor` SPI）启动期一次性 `ConfigService.getConfig(dataId, group, timeout)` → `YamlPropertySourceLoader` 解析 → `addFirst` 注入 Environment（最高优先级）。无 bootstrap.yml、无 Spring Cloud。双降级：`nacos.server-addr` 空→跳过；不可达→catch 降级本地。
- **热更新**：`NacosConfigRefresher`（@Component，`@PostConstruct`）在 `refresh-enabled=true && server-addr 非空` 时 `ConfigService.addListener` 监听变更 → `onConfigChange` 解析 YAML → 以 `nacos-config-live` 活源（`MapPropertySource`）`addFirst`/`replace` 注入 Environment。值需解包 `OriginTrackedValue`（Boot YAML 加载器包裹）。失败只记日志保留旧配置（②每步降级，不影响主链路）。
- **`@RefreshScope` 延后**：Bean 级重注入需 `spring-cloud-context`（Boot 4.1 兼容风险同源）。当前等效热更新靠 Environment 活源 + 各消费方 `refresh()` 收口（如 `ModelConfigCenter.refresh()`）。
- 测试缝：`NacosConfigRefresher.subscribe(...)` 可重写为记录型，避免触达真实 Nacos / 实现全套 `ConfigService` 假件。

**⚠ 写 dataId 的键名（2026-09-09 核对 `src/main/java` 后修正，曾误判）**：`src/main/java` 里 `@ConfigurationProperties` 绑定器只有 3 个 —— `NacosConfig`(`nacos`)、`RedisProperties`(`redis`)、外加主类 `@EnableConfigurationProperties(NacosConfig.class)`。因此 dataId（YAML 同形 `application.yml`）里必须写：
- `spring.datasource.*` / `spring.rabbitmq.*` —— Spring Boot 自动配置读（HikariCP/JPA、`RabbitTemplate`），**不是**根级 `datasource.*` / `rabbitmq.*`
- `redis.*` —— 由 `RedisProperties` 绑定
- **根级 `datasource:` / `rabbitmq:`（`application.yml` 里那两个块）是死配置**：零绑定器、零字面读取，写了静默忽略 —— 应删除以免误导
- `app.rabbitmq.enabled`（`@ConditionalOnProperty`）是业务总开关，与 `spring.rabbitmq.*` 连接参数是**两个独立维度**，都要写对

**⚠ 健康探针根因**：`RabbitHealthIndicator` / `DataRedisReactiveHealthIndicator` 按 `spring.rabbitmq.*` / `spring.redis.*` 自动配置探默认值，**与 `app.rabbitmq.enabled` 无关**。故 dev 无 broker 时的 `Rabbit/Redis health check failed` WARN 是良性的；但 prod 即使 broker 通且业务消费者已装配，只要 `spring.rabbitmq.*` 仍指 `localhost:5672`，health 的 `rabbit` 子项仍是 DOWN。修复：dataId 里指向真实地址（推荐，health 子项也成真实信号），或 `spring.health.rabbit.enabled: false`（Boot 4.1 前缀是 `spring.health.*`，**非** `management.health.*`）。

**Why**：计划原始 `spring.config.import` + `@RefreshScope` 依赖 spring-cloud 生态，Boot 4.1 兼容未冒烟；降级到裸 nacos-client + SPI 是计划预案，保持 Boot 4.1 单体栈纯净。
**How to apply**：后续若接真实 Nacos，只需保证 `nacos.server-addr/namespace/data-id/group` 环境变量就位（`namespace` 必须是 **namespace ID 非显示名**，空串=public，误填显示名不报错只静默降级本地）；写 dataId 严格按上面键名，**别照抄 `application.yml` 的根级死配置块**；`@RefreshScope` 全量热更新待 spring-cloud-context 冒烟通过后再切（计划已标延后 [ ]）。不要把接入机制改回 `spring.config.import` 除非兼容验证通过。完整部署文档见仓库 `DEPLOY.md` §6。

相关：[[nacos-prompt-registry-fit]]、[[phase4-gateway-design]]（ModelConfigCenter.refresh 热加载收口）。
