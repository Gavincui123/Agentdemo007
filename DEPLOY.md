# Agentdemo007 部署指南

> 单 jar 单体部署：前端 SPA（Vue 3 / Vite）构建时直出后端 `classpath:/static/`，由 `mvn` 打入可执行 jar；后端同源托管静态资源并对外提供全部 API 与 Actuator。**一进程即整站**，无需独立前端服务器、无需 Nginx SPA fallback。

---

## 1. 架构与部署形态

```
┌─────────────────────────────────────────────────────────┐
│  Agentdemo007-0.0.1-SNAPSHOT.jar  (单可执行 jar)        │
│                                                         │
│  ┌─────────────┐   ┌──────────────────────────────────┐ │
│  │ 静态资源     │   │ Spring Boot 4.1.1 (Java 17)      │ │
│  │ / (SPA)     │   │  POST /chat        (SSE 流式)     │ │
│  │ hash 路由    │   │  /admin/**         (X-Admin-Token)│ │
│  │ 无 fallback │   │  POST /eval/run     (eval token)  │ │
│  └─────────────┘   │  GET  /api/obs/*   (X-Admin-Token)│ │
│                    │  /actuator/{health,info,metrics,  │ │
│                    │             prometheus}           │ │
│                    └──────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
          :8080  ← 唯一对外端口
```

- **hash 路由**：前端用 `createWebHashHistory`，导航路径（`/#/admin` 等）不发往服务端，浏览器只请求 `/`，与后端 API 路径（`/chat /admin/* /eval/run /api/obs/*`）零冲突 → **无需 SPA fallback 兜底**。
- **降级内核**：外部依赖（LLM / Redis / RabbitMQ / Nacos / MySQL）缺失时程序仍可启动与响应，能力降级而非崩溃（dev 默认零外部依赖可起）。

---

## 2. 前置要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17 | `java -version` 应为 17；`JAVA_HOME` 指向 JDK 17 |
| Node.js | ≥ 20.19 | Vite 8 / TypeScript 6 要求；仅构建期需要 |
| Maven | 无需本地安装 | 项目自带 `mvnw` wrapper |
| MySQL | 8.x | prod 持久化（dev 用 H2 内存库，无需） |
| Redis | 任意稳定版 | 会话缓存（可选） |
| RabbitMQ | 3.x | 异步持久化（prod 强制；dev 默认关闭） |
| Nacos | 3.x（client 3.2.4） | 动态配置（dev 可选，prod 强制） |
| LLM | OpenAI 兼容端点 | 对话能力（缺失则降级话术） |

---

## 3. 快速开始：本地 dev

dev profile 默认 **H2 内存库 + RabbitMQ 关闭 + Nacos 可选 + 令牌有默认值**，零外部依赖即可启动。

**终端 A — 前端 dev server（热更新，:5173，代理后端 :8080）**

```bash
cd frontend
npm install            # 首次
npm run dev            # vite，访问 http://localhost:5173
```

**终端 B — 后端（:8080）**

```bash
export JAVA_HOME=/path/to/jdk-17     # 本机若无 JAVA_HOME
./mvnw spring-boot:run                # 默认 profile=dev
```

打开 http://localhost:5173 即对话页；管理台 `/#/admin`、评测 `/#/eval`、可观测 `/#/obs`（dev 默认令牌 `dev-admin-token` / `dev-eval-token`，失效时在 `/#/auth` 录入）。

> dev 下 Vite 把 `/chat /admin /eval /api /health /info` 反代到 `localhost:8080`，且对 `/chat` 关代理压缩以保 SSE 流式。

---

## 4. 生产部署：单 jar

### 4.1 构建

```bash
# 1) 前端构建 → 直出后端 classpath:/static/（emptyOutDir 自动清空 src 下的旧产物）
cd frontend
npm install
npm run build          # vue-tsc -b 类型检查 + vite build

# 2) 后端打包（必须 clean，见 4.3）
cd ..
./mvnw clean package    # 含测试；快速打包加 -DskipTests
```

产物：`target/Agentdemo007-0.0.1-SNAPSHOT.jar`（约 89 MB，含前端 SPA + 全部依赖）。

### 4.2 运行

```bash
export SPRING_PROFILES_ACTIVE=prod
export ADMIN_TOKEN=<strong-admin-token>      # 生产必改
export EVAL_TOKEN=<strong-eval-token>        # 生产必改
export DS_URL=jdbc:mysql://<host>:3306/agentdemo007?useSSL=true\&serverTimezone=UTC
export DS_USERNAME=<db-user>
export DS_PASSWORD=<db-password>
export DS_DRIVER=com.mysql.cj.jdbc.Driver
export LLM_BASE_URL=<https://your-llm-endpoint>
export LLM_API_KEY=<llm-key>
# …其余见第 5 节按需注入
java -jar target/Agentdemo007-0.0.1-SNAPSHOT.jar
```

### 4.3 ⚠ 必须 `clean`：避免死 chunk 累积

`mvn package`（不带 `clean`）会在 jar 里累积**死 chunk**：

- `maven-resources-plugin` 只把 `src/main/resources/*` 复制进 `target/classes/`，**不删除孤儿文件**；
- Vite content-hash 每次构建都给 chunk 换名（`AdminView-B9pK4POH.js` → 下次又变）；
- 于是每次非-clean 重打包都把上一轮旧 hash chunk（`ObservabilityView` 约 1 MB/个）留在 jar。

**验证**：`mvn clean package` 产出的 jar 含 15 个 chunk；`mvn package`（无 clean）会含 24 个（含 stale）。**部署/CI 一律 `mvn clean package`**。

### 4.4 验证

```bash
curl -i http://localhost:8080/actuator/health      # {"status":"UP"}
curl -i http://localhost:8080/                      # 返回 SPA index.html
curl -i -H "X-Admin-Token: $ADMIN_TOKEN" \
     http://localhost:8080/api/obs/summary          # 16 字段可观测快照
```

---

## 5. 环境变量参考

> 全部密钥经环境变量注入，配置文件仅 `${}` 占位，不落明文。下表「默认」列为 dev 行为；prod 见「prod」列。

### 5.1 启动 / Profile

| 变量 | 默认 | prod | 说明 |
|------|------|------|------|
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` | 激活 profile |
| `SERVER_PORT` | `8080` | `8080` | 对外端口（`server.port`） |

### 5.2 数据源 / JPA

| 变量 | 默认 (dev) | prod | 说明 |
|------|-----------|------|------|
| `DS_URL` | `jdbc:h2:mem:agentdemo007;DB_CLOSE_DELAY=-1;MODE=MySQL` | MySQL JDBC | 数据库连接 |
| `DS_USERNAME` | _(空)_ | 必填 | |
| `DS_PASSWORD` | _(空)_ | 必填 | |
| `DS_DRIVER` | `org.h2.Driver` | `com.mysql.cj.jdbc.Driver` | JDBC 驱动 |
| `JPA_DDL` | `update` | `none`（固定） | 表结构管理策略 |
| `JPA_SHOW_SQL` | `true` | `false` | SQL 日志 |

> prod 由 `classpath:schema.sql` 建表（`spring.sql.init.mode=always`，`IF NOT EXISTS` 幂等，每次启动安全），`JPA_DDL=none`。项目未集成 Flyway/Liquibase——结构演进需手动改 `schema.sql` 或引入迁移工具，见第 8 节。

### 5.3 Redis（会话缓存）

| 变量 | 默认 | 说明 |
|------|------|------|
| `REDIS_HOST` | `localhost` | |
| `REDIS_PORT` | `6379` | |
| `REDIS_PASSWORD` | _(空)_ | prod 必填 |
| `REDIS_DATABASE` | `0` | |
| `REDIS_TIMEOUT` | `5000ms` | |
| `REDIS_SESSION_TTL` | `3600s` | 会话 TTL |

### 5.4 RabbitMQ（异步持久化）

| 变量 | 默认 (dev) | prod | 说明 |
|------|-----------|------|------|
| `RABBITMQ_ENABLED` | `false` | `true`（强制） | **总开关**：false→不连 broker、NoopMessagePublisher、会话/审计仅记 log |
| `RABBITMQ_HOST` | `localhost` | 必填 | |
| `RABBITMQ_PORT` | `5672` | | |
| `RABBITMQ_USERNAME` | `guest` | 必填 | |
| `RABBITMQ_PASSWORD` | `guest` | 必填 | |
| `RABBITMQ_VIRTUAL_HOST` | `/` | | |
| `RABBITMQ_MAX_ATTEMPTS` | `3` | | 消费重试次数（毒消息 nack→DLQ） |

### 5.5 Nacos（动态配置）

| 变量 | 默认 (dev) | prod | 说明 |
|------|-----------|------|------|
| `NACOS_SERVER_ADDR` | _(空=跳过)_ | 必填 | 空→dev 优雅跳过 |
| `NACOS_NAMESPACE` | _(空)_ | | 命名空间 |
| `NACOS_GROUP` | `DEFAULT_GROUP` | | |
| `NACOS_DATA_ID` | `Agentdemo007` | | |
| `NACOS_REFRESH_ENABLED` | `true` | `true`（强制） | 热更新 |
| `NACOS_CONFIG_TIMEOUT_MS` | `5000` | `10000` | |

### 5.6 LLM（OpenAI 兼容端点）

| 变量 | 默认 | 说明 |
|------|------|------|
| `LLM_BASE_URL` | _(空)_ | OpenAI 兼容端点 |
| `LLM_API_KEY` | _(空)_ | 缺失→对话降级话术 |
| `LLM_MODEL_ID` | `gpt-4o` | 主模型 |
| `LLM_SMALL_MODEL_ID` | `gpt-4o-mini` | 辅助模型（改写等） |
| `LLM_TIMEOUT_MS` | `60000` | |
| `LLM_MAX_RETRY` | `3` | |

### 5.7 鉴权令牌

| 变量 | 默认 | 说明 |
|------|------|------|
| `ADMIN_TOKEN` | `dev-admin-token` | 守 `/admin/**` + `/api/obs/**`；前端 `X-Admin-Token` 头注入 |
| `EVAL_TOKEN` | `dev-eval-token` | 守 `POST /eval/run`；前端评测页输入框提供 |

> prod 一律改强令牌。鉴权在后端 `AdminAuthInterceptor`（路径级）/ `EvalAuthenticator`：未授权返回 HTTP 200 + 体内 `code:401`（话术短路，不暴露技术栈）。

### 5.8 业务参数

| 变量 | 默认 | 说明 |
|------|------|------|
| `MODEL_SELECTOR_STRATEGY` | `tag` | 模型选择策略 `tag\|weight\|cost` |
| `PIPELINE_MODE` | `linear` | 编排模式 `linear\|graph`（LangGraph） |
| `LANGGRAPH_MAX_ITERATIONS` | `25` | 图模式单请求最大迭代（死循环护栏） |
| `TOOL_MAX_ITERATIONS` | `3` | 工具自纠正最大迭代 |
| `OUTPUT_MAX_RETRIES` | `2` | 结构化输出 Schema 校验重试 |
| `HITL_TIMEOUT_MS` | `300000` | 人工审批超时（毫秒） |
| `RAG_TOP_K` | `5` | RAG 召回上限 |
| `RAG_MIN_SCORE` | `0.3` | RAG 相关性阈值 |
| `RAG_MIN_COUNT` | `1` | RAG 数量下限 |
| `RAG_SEED_ENABLED` | `true` | 种子语料开关 |
| `AUDIT_ENABLED` | `true` | 审计开关 |
| `AUDIT_RETENTION_DAYS` | `180` | 审计留存天数 |

---

## 6. Nacos 配置中心（正式部署测试）

本项目**不用 Spring Cloud Alibaba**，而是以 `nacos-client 3.2.4` 直连 Boot 4.1（Phase 2）。两阶段：启动期一次性拉取 + 运行期监听热更新。

### 6.1 接入机制（已核对代码）

| 环节 | 实现 |
|------|------|
| 启动加载 | `NacosEnvironmentPostProcessor`，经 `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports` 注册（Boot 4.1 的 SPI 方式；本项目**没有** `spring.factories`） |
| 热更新 | `NacosConfigRefresher`（`@Component`，`@PostConstruct` 注册监听） |
| 解析器 | **`YamlPropertySourceLoader`** —— dataId 内容是 **YAML**，键结构与 `application.yml` 完全同形，加载后扁平化为点号键 |
| 加载位置 | `propertySources.addFirst(...)` → **Nacos 优先级高于环境变量与 `application.yml`** |
| source 名 | 启动期 `nacos-config`；热更新 `nacos-config-live`（可替换，重复推送不累积） |
| 降级 | Nacos 不可达 / 内容为空 / 内容解析失败 → 记 WARN，**回退本地配置，不阻塞启动** |

### 6.2 namespace 怎么写（关键）

`NACOS_NAMESPACE` 必须填 **namespace ID，不是显示名**。

- 在 Nacos 控制台「命名空间」页，复制 **ID 列**（一串 16 位随机串，形如 `a1b2c3d4-5e6f-...`）；显示名只给人看
- `public` 的 ID 是**空串** —— 想走 public，`NACOS_NAMESPACE` 留空即可
- 代码行为：`nacos.namespace` 为空时**不向客户端推送 namespace 参数**，nacos-client 默认落到 `public`
- ⚠ 误填显示名不会报错，只会拉不到配置并静默降级到本地配置 —— 见 6.7 排查

### 6.3 dataId 与 group

默认 `dataId=Agentdemo007`、`group=DEFAULT_GROUP`，均可用环境变量覆盖。建议建专用 dataId，**格式选 `yaml`**（不是 properties/json）。

### 6.4 环境变量 vs Nacos 的分界（鸡生蛋）

这 **6 个只能放环境变量**，不能放进 Nacos（否则先有鸡还是先有蛋）：

`NACOS_SERVER_ADDR` / `NACOS_NAMESPACE` / `NACOS_GROUP` / `NACOS_DATA_ID` / `NACOS_REFRESH_ENABLED` / `NACOS_CONFIG_TIMEOUT_MS`

其余配置都可放 Nacos。**同名键 Nacos 赢**（`addFirst`），所以 dataId 里直接写解析后的字面值即可，不必写 `${LLM_API_KEY:}` 这种占位。

### 6.5 完整 dataId 示例（可直接粘贴）

```yaml
# dataId: Agentdemo007    group: DEFAULT_GROUP    格式: yaml

spring:
  datasource:
    url: jdbc:mysql://<host>:3306/agentdemo007?useSSL=true&serverTimezone=UTC&characterEncoding=utf8
    username: <db-user>
    password: <db-password>
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate.ddl-auto: none
    show-sql: false
  sql.init.mode: always
  rabbitmq:
    host: <rabbit-host>
    port: 5672
    username: <rabbit-user>
    password: <rabbit-password>
    virtual-host: /

redis:
  host: <redis-host>
  port: 6379
  password: <redis-password>
  database: 0
  timeout: 5000ms
  session-ttl: 3600s

app:
  rabbitmq.enabled: true
  pipeline.mode: linear
  rag:
    top-k: 5
    min-score: 0.3
    min-count: 1
    seed.enabled: true

llm:
  openai:
    base-url: <https://your-llm-endpoint>
    api-key: <llm-key>
    model-id: <主模型>
    small-model-id: <辅助模型>
    timeout-ms: 60000
    max-retry: 3

agentdemo:
  admin:
    token: <strong-admin-token>
  eval:
    token: <strong-eval-token>

audit:
  enabled: true
  retention-days: 180
```

**⚠ 键名陷阱（已核对 `src/main/java`）**

- 写 `spring.datasource.*` / `spring.rabbitmq.*` —— 由 Spring Boot 自动配置读取（`RabbitTemplate`、HikariCP/JPA）
- 写 `redis.*` —— 由本项目 `RedisProperties`（`@ConfigurationProperties(prefix = "redis")`）绑定
- **不要写根级 `datasource.*` / `rabbitmq.*`** —— `application.yml` 里那两个根级块是**死配置**：代码里没有对应绑定器、没有字面读取，写了也是静默忽略
- **业务开关是 `app.rabbitmq.enabled`**（`@ConditionalOnProperty(prefix = "app.rabbitmq", name = "enabled")`）—— 它与 `spring.rabbitmq.*` 是两个独立维度：前者决定是否装配业务消费者，后者决定 broker 连接参数。两者都要写对

### 6.6 启动命令

```bash
export SPRING_PROFILES_ACTIVE=prod
export NACOS_SERVER_ADDR=nacos.internal:8848
export NACOS_NAMESPACE=<namespace-id>        # 留空 = public
export NACOS_GROUP=DEFAULT_GROUP
export NACOS_DATA_ID=Agentdemo007
export NACOS_REFRESH_ENABLED=true
export NACOS_CONFIG_TIMEOUT_MS=10000
java -jar target/Agentdemo007-0.0.1-SNAPSHOT.jar
```

### 6.7 验证与排查

```bash
# 1) 启动日志确认加载
grep -E "Nacos 配置加载成功|Nacos 热更新监听已启动|Nacos 配置中心未配置|Nacos 配置加载" /var/log/agentdemo007/*.log

# 2) 健康
curl -i http://localhost:8080/actuator/health

# 3) 热更新：改 dataId 里 rag.top-k，数秒后应看到刷新日志
grep "热更新" /var/log/agentdemo007/*.log | tail
```

预期：`Nacos 配置加载成功: dataId=Agentdemo007, group=DEFAULT_GROUP` + `Nacos 热更新监听已启动`。

**配置没生效时，先看有没有 WARN：**

| 日志 | 含义 / 处理 |
|------|-------------|
| `Nacos 配置为空` | dataId / group / **namespace 写错**（最常见：填了显示名而非 ID） |
| `Nacos 配置加载失败` / `...加载异常` | 连接失败，或 **YAML 语法错**。解析失败走 `catch (Exception)` → **整份配置被静默丢弃**，不会只跳过坏的那一行 |
| `Nacos 配置热更新解析失败（保持旧配置）` | 推送的 YAML 有语法错，保留旧配置不中断 |
| `Nacos 配置中心未配置 (nacos.server-addr 为空)` | 环境变量没注入，走本地配置（dev 预期行为） |

### 6.8 启动 WARN：Rabbit / Redis health check failed

```
Rabbit health check failed
Redis health check failed
Connection refused: localhost:5672 / 6379
```

这是 **Actuator 健康探针**（`RabbitHealthIndicator` / `DataRedisReactiveHealthIndicator`）按 `spring.rabbitmq.*` / `spring.redis.*` 的自动配置去探默认值。**与 `app.rabbitmq.enabled` 无关** —— 即使业务消费者装配好了、broker 也通，只要 `spring.rabbitmq.*` 还指向 `localhost:5672`，health 的 `rabbit` 子项就是 DOWN。

dev 下这是良性的（降级内核：应用照常启动与响应）。prod 两条路：

1. **在 Nacos dataId 里把 `spring.rabbitmq.*` / `spring.redis.*` 指向真实地址**（推荐 —— health 子项也是真实信号）
2. 明确不需要该子项时，关闭探针：`spring.health.rabbit.enabled: false`、`spring.health.redis.enabled: false`

### 6.9 热更新的能力边界

**没有 `@RefreshScope`**（需 `spring-cloud-context`，与 Boot 4.1 的兼容风险未评估）。当前等价物是 Environment 实时源 `nacos-config-live` 常驻 + 各消费者自己实现 `refresh()`。

| 读取方式 | 热更新是否生效 |
|----------|----------------|
| `Environment.getProperty(...)` 每次调用读取 | ✅ 实时 |
| `@Value("${...}")` 注入 bean 字段 / 构造参数 | ❌ 需重启 |
| 有 `refresh()` 且被调用的消费者 | ✅ |
| `@ConfigurationProperties` 绑定的 bean（如 `RedisProperties`） | ⚠ 默认不刷新，需自行触发 |

改完没生效，先确认消费者的读取方式 —— 这不是 bug。

### 6.10 Nacos 3.x / compose 注意事项

- **端口冲突**：`docker-compose.yml` 中 Nacos 容器同时映射了 `8080:8080`（Nacos 控制台），与应用 `server.port=8080` 冲突。容器部署时改应用端口，或去掉 Nacos 的 8080 映射
- **Nacos 自身需要 MySQL**：compose 用 `SPRING_DATASOURCE_PLATFORM=mysql` + `MYSQL_SERVICE_DB_NAME=nacos_config`，需先在 MySQL 建 `nacos_config` 库
- **鉴权已开启**：`NACOS_AUTH_TOKEN` / `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE`。当前 `nacos-client` 直连方式**不传用户名密码** —— 内网部署可接受；**暴露公网前必须补鉴权**
- **compose 里的密码是明文示例**（`MYSQL_SERVICE_PASSWORD` 等），生产必须替换

### 6.11 安全说明

**权衡点**：本项目约定「密钥经环境变量注入，配置文件仅 `${}` 占位，不落明文」。但 Nacos 优先级高于环境变量、且内容是解析后的字面值 —— 等于**密钥以明文形式存放在 Nacos**（由 Nacos 鉴权保护）。

这是有意识的取舍：动态配置中心的价值（免重启改配置）与「密钥不落盘」在此冲突。缓解与残留风险：

- ✅ Nacos 鉴权开启（`NACOS_AUTH_TOKEN`）
- ✅ dataId 权限收敛到配置管理角色
- ⚠ 仍在 Nacos 存储层（MySQL `nacos_config`）以明文可读
- ⚠ **建议 prod 把 `ADMIN_TOKEN` / `EVAL_TOKEN` / `LLM_API_KEY` 留在环境变量**，只把非敏感项（RAG / HITL / 模型选择等）放 Nacos；或引入 Vault 类方案

**已知缺口（诚实标注）**

1. `application-prod.yml` 注释写「密钥必须通过环境变量提供，缺省为空（启动校验拒绝明文）」—— **代码里没有这个校验类**。当前 prod 缺密钥只是拿到空字符串，LLM 走降级话术，不会启动失败。若要让「缺密钥即拒启」成为真实行为，需补一个启动期 validator
2. `application.yml` 的根级 `datasource:` / `rabbitmq:` 块是死配置（无绑定器、无读取），应删除以免误导
3. 仓库内没有任何 `management.health.*` / `spring.health.*` 覆盖配置，dev 的 Rabbit/Redis WARN 目前靠约定忽略

---

## 7. 外部依赖：何时需要

| 依赖 | dev | prod | 缺失时行为 |
|------|-----|------|-----------|
| MySQL | ❌（H2 内存） | ✅ | — |
| RabbitMQ | ❌（默认关） | ✅（强制） | `RABBITMQ_ENABLED=false`→NoopMessagePublisher，会话/审计不入库仅记 log |
| Nacos | ⚪（空则跳过） | ✅ | dev 跳过；prod 不可缺 |
| Redis | ⚪ | 推荐 | 缺失→会话缓存降级 |
| LLM | ⚪ | ✅ | 缺失→对话返回降级话术，程序仍起 |

dev 的「零依赖可起」是降级内核的体现：任意依赖故障，程序持续可用，空状态返回零值而非报错。

---

## 8. 数据库初始化（prod）

prod 由 `classpath:schema.sql`（`src/main/resources/schema.sql`）建表，`application-prod.yml` 设 `spring.sql.init.mode=always` 使 MySQL 非内嵌库启动时执行；`JPA_DDL=none`，JPA 不再自动建表。

- **幂等**：脚本全部 `CREATE TABLE IF NOT EXISTS`，`mode=always` 反复启动安全。
- **两张表**：`chat_turn`（会话轮次）、`audit_event`（审计事件），逐字段对齐 JPA 实体 `@Column`。
- **dev**：H2 走 `ddl-auto=update` 自建表，`mode=embedded` 执行 `schema.sql` 时表已存在 → 跳过，零冲突。

> 项目未集成 Flyway/Liquibase。结构演进时：改 `schema.sql`（加 `IF NOT EXISTS` 幂等语句；勿加 `ALTER`/`INSERT` 这类非幂等语句），或手动 DBA 执行迁移。引入迁移工具后此节作废。
>
> **推荐索引**（脚本注释列出，未纳入 always 幂等脚本，因 MySQL 不支持 `CREATE INDEX IF NOT EXISTS`）：`chat_turn(session_id, turn_timestamp)`、`audit_event(trace_id)`、`audit_event(created_at)`。首次建表后手动执行。

---

## 9. 健康检查与可观测

### 9.1 Actuator（默认暴露）

| 端点 | 用途 |
|------|------|
| `GET /actuator/health` | 存活探针（`show-details: always`，含 DB/MQ/Redis 健康子项） |
| `GET /actuator/info` | 应用信息 |
| `GET /actuator/metrics` | Micrometer 指标 |
| `GET /actuator/prometheus` | Prometheus 抓取 |

> K8s liveness/readiness 探针指向 `/actuator/health`。

### 9.2 前端管理台（hash 路由）

| 路由 | 职能 | 鉴权 |
|------|------|------|
| `/#/chat` | 对话界面（SSE 流式） | 无 |
| `/#/admin` | 模型权重 / HITL 审批 / 会话历史回放 | `X-Admin-Token` |
| `/#/eval` | 黄金样例评测（8 环节通过率） | eval token |
| `/#/obs` | 全链路遥测快照（16 字段 + ECharts） | `X-Admin-Token` |
| `/#/auth` | 令牌录入（401 后回退至此） | 无 |

前端在 `localStorage` 存 admin token，经 `http.ts` 请求拦截器自动注入 `X-Admin-Token`；令牌失效（后端 401）→ 前端清令牌并回退 `/#/auth?redirect=...`。

---

## 10. 日志

`logback-spring.xml` 配置：

- dev：`logs/agentdemo007.log`，级别 `com.agentdemo007=DEBUG`、`intent=TRACE`
- prod：`/var/log/agentdemo007`，级别 `INFO`

容器部署时挂载 `/var/log/agentdemo007` 或改 `logging.file.path`。

---

## 11. 已知限制与故障排查

| 现象 | 原因 / 处理 |
|------|------------|
| jar 体积随构建递增 | 非 `clean` 打包累积死 chunk → 一律 `mvn clean package`（4.3） |
| `ObservabilityView` chunk ~1 MB | 全量 ECharts 打入该懒加载块，仅 `/#/obs` 按需加载；后续可改 ECharts 按需 import 瘦身 |
| prod 启动报表不存在 | `schema.sql` 未执行 → 确认 `spring.sql.init.mode=always`（prod 默认已设）；首次建表见第 8 节 |
| 对话返回话术而非回复 | `LLM_API_KEY`/`LLM_BASE_URL` 未配 → LLM 降级；配置后恢复 |
| 管理台 401 | `ADMIN_TOKEN` 不匹配 → `/#/auth` 录入正确令牌 |
| dev 不连 RabbitMQ 正常 | `RABBITMQ_ENABLED` 默认 false，NoopMessagePublisher，会话/审计仅记 log |
| `npx vue-tsc` 报 `ERR_PACKAGE_PATH_NOT_EXPORTED` | npx 缓存与 TS6 不兼容 → 用 `npm run build` / `npm run typecheck`（本地 `node_modules/.bin/vue-tsc`） |

---

## 12. 容器化（可选示例，项目未内置 Dockerfile）

多阶段构建：Node 阶段产前端产物 → JKD 阶段打 jar 运行。

```dockerfile
# ---- 前端构建 ----
FROM node:20-alpine AS fe
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build    # 直出 ../src/main/resources/static/

# ---- 后端构建 ----
FROM maven:3.9-eclipse-temurin-17 AS be
WORKDIR /app
COPY . .
COPY --from=fe /app/src/main/resources/static ./src/main/resources/static
RUN ./mvnw clean package -DskipTests

# ---- 运行 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=be /app/target/Agentdemo007-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
docker build -t agentdemo007:0.0.1 .
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e ADMIN_TOKEN=... -e EVAL_TOKEN=... \
  -e DS_URL=... -e DS_USERNAME=... -e DS_PASSWORD=... \
  -e LLM_BASE_URL=... -e LLM_API_KEY=... \
  agentdemo007:0.0.1
```

---

## 附：一页速查

```bash
# 构建
cd frontend && npm install && npm run build && cd ..
./mvnw clean package -DskipTests

# 运行（dev，零依赖可起）
./mvnw spring-boot:run

# 运行（prod）
SPRING_PROFILES_ACTIVE=prod \
ADMIN_TOKEN=... EVAL_TOKEN=... \
DS_URL=... DS_USERNAME=... DS_PASSWORD=... \
LLM_BASE_URL=... LLM_API_KEY=... \
java -jar target/Agentdemo007-0.0.1-SNAPSHOT.jar

# 验证
curl localhost:8080/actuator/health
curl -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/obs/summary
```
