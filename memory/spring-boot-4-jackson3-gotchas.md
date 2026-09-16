---
name: spring-boot-4-jackson3-gotchas
description: Spring Boot 4.1 / Jackson 3 / Spring 7 migration gotchas hit while building Phase 1-2
metadata: 
  node_type: memory
  type: reference
  originSessionId: a26da8fd-8303-4e65-9245-1439cbae880f
  modified: 2026-09-06T13:53:23.215Z
---

Spring Boot 4.1.1 用的 Jackson 3 / Spring Framework 7，与旧代码有几处不兼容坑（Phase 1-2 已踩并修复）：

1. **Jackson 3 包名从 `com.fasterxml.jackson` 迁到 `tools.jackson`**。生产里序列化走 Spring 自动转换；测试手动用 `tools.jackson.databind.ObjectMapper`。`YamlPropertySourceLoader` 等 Spring Boot 类照常用。

2. **`spring.jackson.serialization.write-dates-as-timestamps` 已删**（Jackson 3 `SerializationFeature` 无此常量），写在 application.yml 会导致属性绑定失败、上下文加载失败。日期格式改在代码里用 `DateTimeFormatter.ISO_OFFSET_DATE_TIME` 手动格式化。

3. **`HttpStatus.PAYLOAD_TOO_LARGE` 重命名为 `CONTENT_TOO_LARGE`**（RFC 9110）。旧名若仍存在是 deprecated 别名，与 `CONTENT_TOO_LARGE` 是**不同枚举实例**，`isEqualTo` 会失败。统一用 `CONTENT_TOO_LARGE`。

4. **profile 专用文件 `application-{profile}.yml` 内禁止写 `spring.profiles.active`**，否则 `InvalidConfigDataPropertyException`。profile 激活只在 `application.yml` 里写一次。

5. `ResponseErrorHandler` 的 `handleError(ClientHttpResponse)` 已是 default 方法，测试里只重写 `hasError` 即可。

6. **`@EnableConfigurationProperties` 在 Boot 4 已删**（Phase 3 踩）。`@ConfigurationProperties` 类直接用 `@Bean` 方法返回即可绑定（与 NacosConfig 同模式，已被 `NacosConfigTest` 验证）。不要再 `import org.springframework.boot.autoconfigure.context.EnableConfigurationProperties`。

7. **Spring Data Redis `ValueOperations.set` 有 4 参重载歧义**（`set(K,V,Duration)` 与 `set(K,V,long,TimeUnit)`，都返回 Long）。Mockito `verify(ops).set(...)` 用 4 参 + `eq(Duration)` 会触发 `incompatible bounds`。规避：用 3 参 `set(key,value,Duration)` 对应生产调用，或在测试用 `ArgumentCaptor<Duration>` 捕获后断言。

8. **Boot 4.1.1 已移除 `@DataJpaTest` 与 `@AutoConfigureTestDatabase` 测试切片**（Phase 13 踩）。`spring-boot-test-autoconfigure:4.1.1` 仅 22 个类，无 `orm/jpa` 子包；`spring-boot-jpa`/`spring-boot-data-jpa` 4.1.1 jar 内也搜不到这两个类——所有 4.1.1 jar 全无。替代方案：repository 测试改用 `@SpringBootTest`（与 `contextLoads` 共享缓存上下文，启动快）+ `@Transactional`（每测试回滚隔离），H2 内存库 + `ddl-auto=update` 自动建表。OffsetDateTime→`timestamp(6) with time zone`、`@Enumerated(STRING)`→H2 `enum(...)`、`columnDefinition="TEXT"` 均正常。

后续 Phase 涉及 Jackson/HttpStatus/多 profile / Redis / JPA 测试切片时优先套用以上结论。相关：[[java-home]]、[[phase4-gateway-design]]
