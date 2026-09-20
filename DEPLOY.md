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
export LLM_ENABLED=true                       # 模型主备 providers 走 Nacos dataId（§6.5）
export SF_KEY=<siliconflow-key>               # dataId ${SF_KEY} 占位实参（ALIYUN_KEY 同理）
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
| `NACOS_SERVER_ADDR` | `120.48.5.195:8848` | 必填 | yml 自带默认（指向测试服务器）；prod 换内网地址 |
| `NACOS_NAMESPACE` | _(空)_ | | 必须填 namespace **ID**（public 留空）—— 见 6.2 |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | _(空)_ | | 服务器 Nacos 开鉴权才需要 |
| `NACOS_GROUP` | `DEFAULT_GROUP` | | 仅组件默认 group；本项目 import URL 已钉死 group，对该 dataId 不生效 |

> dataId 固定 `Agentdemo007.yaml`（`application.yml` import URL 声明，无环境变量）；机制详见 §6。

### 5.6 LLM（多服务商主备，OpenAI 兼容）

| 变量 | 默认 | 说明 |
|------|------|------|
| `LLM_ENABLED` | `false` | `true` → 按 `llm.providers`（Nacos dataId，结构见 6.5）装配路由执行器 + 模型级熔断 + 主备故障转移；false/缺省 → dev Noop 占位（下游收口 MODEL_DOWN 话术短路） |
| `SF_KEY` / `ALIYUN_KEY` | — | dataId 里 `${SF_KEY}` / `${ALIYUN_KEY}` 占位的实参——放服务器环境变量，明文不落 Nacos/仓库 |

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
| `TOOL_MAX_ITERATIONS` | `2` | 工具环节 Agent loop 轮次上限（探测→执行→失败回喂自纠正/澄清；`1`=退化为单次前向；耗尽仍失败→错误结果交终答 LLM 如实说明） |
| `TOOL_TIMEOUT_MS` | `10000` | 单次工具执行超时（守护线程硬中断，`0`=关闭；超时按瞬态退避重试） |
| `TOOL_RETRY_MAX_ATTEMPTS` | `3` | 单工具调用总尝试次数（含首次；仅瞬态失败：超时/5xx/未知异常） |
| `TOOL_RETRY_INITIAL_BACKOFF_MS` | `100` | 重试退避基数（指数 ×2 + 全抖动） |
| `TOOL_RETRY_MULTIPLIER` | `2.0` | 重试退避倍率 |
| `TOOL_RETRY_MAX_BACKOFF_MS` | `10000` | 单次退避封顶 |
| `TOOL_RETRY_JITTER` | `true` | 退避全抖动 `[0, base]` |
| `OUTPUT_MAX_RETRIES` | `2` | 结构化输出 Schema 校验重试 |
| `HITL_TIMEOUT_MS` | `300000` | 人工审批超时（毫秒） |
| `HITL_CHECKPOINT_REDIS_TTL` | `24h` | 挂起检查点 Redis 热副本 TTL（`REDIS_ENABLED=true` 时生效；DB 恒为持久真相源，写入均异步） |
| `GATE_ENABLED` | `false` | 访问闸口统一旋钮（本地兜底值；**发布口径以 Nacos `agentdemo-gate.json` 为准**，热更新）。`true` 一次性生效：① /chat 需访问口令（前端 `/gate` 登录页 + 会话页常驻入口）；② 外部 IP 每自然日对话限额（localhost 豁免）；③ `/eval/**` 仅限本机 |
| `GATE_ACCESS_CODE` | — | 访问口令（本地兜底；生产放 Nacos，勿入环境变量明文） |
| `GATE_DAILY_LIMIT` | `5` | 每外部 IP 每日对话轮数（配额存储：`REDIS_ENABLED=true` → Redis INCR+48h TTL 重启不丢；否则内存重启清零） |
| `GATE_REQUIRED_MESSAGE` / `GATE_EXHAUSTED_MESSAGE` | 见 yml | 口令缺失/额度用尽的面向用户话术 |
| `GATE_SOURCE` | `nacos` | 闸口规则来源（`local`=恒本地值，不接 Nacos） |
| `RAG_RECALL_DENSE` | `24` | 稠密召回预算（生产大库调 50+） |
| `RAG_RECALL_SPARSE` | `24` | 稀疏（BM25）召回预算 |
| `RAG_PREFILTER_MIN_SCORE` | `0.2` | 重排前宽松粗滤阈值（只杀确定垃圾省 rerank 成本，0=关闭） |
| `RAG_RERANK_TOP_N` | `10` | 重排 top_n = 注入上限（池 ≤ top_n 跳过重排） |
| `RAG_MIN_SCORE` | `0.3` | 置信度终闸：未重排片段 cosine 阈值（真库部署建议 `0.4`） |
| `RAG_RERANK_MIN_SCORE` | `0.3` | 置信度终闸：被远程重排片段 relevance 阈值 |
| `RAG_MIN_COUNT` | `1` | RAG 数量下限（不足 → RAG_SKIP 话术兜底） |
| `RAG_SEED_ENABLED` | `true` | 种子语料开关（真库模式恒禁用） |
| `VECTORSTORE_TYPE` | `inmemory` | 向量库类型 `inmemory\|chroma`（真库须配合 `EMBEDDING_ENABLED=true`） |
| `CHROMA_BASE_URL` | `http://localhost:8001` | Chroma v2 API 地址（同机部署 localhost，远程填 host:port） |
| `CHROMA_COLLECTION` | `kb_customer_service` | Chroma 集合名（与入库流水线一致） |
| `CHROMA_TOKEN` | — | Chroma Bearer token（自建无鉴权留空） |
| `LUCENE_INDEX_DIR` | `./data/lucene-bm25` | Lucene BM25 磁盘倒排索引目录（MMap 落盘，堆内存极小；启动从 Chroma 流式同步，建议持久盘） |
| `AUDIT_ENABLED` | `true` | 审计开关 |
| `AUDIT_RETENTION_DAYS` | `180` | 审计留存天数 |

---

## 6. Nacos 配置中心（正式部署测试）

本项目以官方 **`spring-alibaba-nacos-config` 2025.1.0.0**（Boot 4.1 原生线，独立组件不拖 Spring Cloud 全家桶）接入：`spring.config.import` 声明 dataId，启动期拉取 + 运行期组件内置监听热更新。

### 6.1 接入机制（已核对代码与组件 jar 字节码）

| 环节 | 实现 |
|------|------|
| 启动加载 | `application.yml` 顶部 `spring.config.import: optional:nacos:Agentdemo007.yaml?group=DEFAULT_GROUP` —— **dataId 须带 `.yaml` 扩展名**，组件按扩展名选 YAML 解析器 |
| SPI 注册 | 组件自带 `spring.factories`：`NacosConfigDataLocationResolver` / `NacosConfigDataLoader`（Boot 标准 ConfigData SPI） |
| 连接参数 | `spring.nacos.config.*`（server-addr / namespace / username / password / file-extension），经环境变量注入（见 6.4） |
| 热更新 | 组件内置 `NacosContextRefresher`（ApplicationReady 后对 dataId 注册 nacos-client 监听，日志 `[Nacos Config] Listening config`）→ 内容变更发布 `NacosConfigRefreshEvent` → 该 dataId 的 property source **原位 `replace` 进 Environment**（生效边界见 6.9 —— 仅实时读取方，bean 不重建） |
| 热更新边界 | ⚠ 重绑 ≠ 生效：仅「运行期每次读取 properties」的 bean 才真热。本项目消费方式多为**构造期装配**（intent 关键词表 `IntentConfig.ruleMatcher(props)`、redis / chroma 客户端、`app.rag.*` 漏斗旋钮、`vectorstore.type` 门控、`llm.enabled`）—— **改这些需重启**，生产改配置以重启为准 |
| 优先级 | **OS 环境变量 > Nacos dataId > 本地 `application.yml`**（config data 高于声明它的文件；env 变量始终高于文件类 source） |
| 降级 | Nacos 不可达 → `optional:` 优雅跳过（启动日志可见），降级本地配置**不阻塞启动**；dataId 内容 YAML 语法错 → 配置数据解析异常**启动失败**（修 dataId 后重启） |

### 6.2 namespace 怎么写（关键）

`NACOS_NAMESPACE` 必须填 **namespace ID，不是显示名**。

- 在 Nacos 控制台「命名空间」页，复制 **ID 列**（一串 16 位随机串，形如 `a1b2c3d4-5e6f-...`）；显示名只给人看
- `public` 的 ID 是**空串** —— 想走 public，`NACOS_NAMESPACE` 留空即可（`spring.nacos.config.namespace` 为空 → 客户端默认落 `public`）
- ⚠ 误填显示名不会报错，只会拉不到配置并被 `optional:` 跳过、降级到本地配置 —— 见 6.7 排查

### 6.3 dataId 与 group

dataId 固定 `Agentdemo007.yaml`、group 固定 `DEFAULT_GROUP`——都写在 `application.yml` 的 import URL 里（要换名需改 yml，或把 import 值改成 `${NACOS_DATA_ID:Agentdemo007.yaml}` 占位）。Nacos 控制台上该 dataId 的**格式必须选 `yaml`**（不是 properties/json）。

### 6.4 环境变量 vs Nacos 的分界（鸡生蛋）

连接参数**只能放环境变量**，不能放进 Nacos（否则先有鸡还是先有蛋）：

`NACOS_SERVER_ADDR` / `NACOS_NAMESPACE` / `NACOS_USERNAME` / `NACOS_PASSWORD`（服务器 Nacos 开鉴权才需后两个）

- dataId 与 group 已在 import URL 固定，无对应环境变量（`NACOS_GROUP` 仅作组件默认 group，对本项目被 URL 钉死的 dataId 不生效）
- **密钥不落 Nacos**：dataId 里写 `${SF_KEY}` / `${ALIYUN_KEY}` 占位，值放服务器环境变量，运行期由 Spring 占位符解析
- 同名键 **env 变量赢**（见 6.1 优先级）——临时覆盖某配置不必改 dataId，导出同名环境变量即可

### 6.5 完整 dataId 示例（可直接粘贴）

> 2026-09-17 更新：真实 RAG（Chroma 稠密 + Lucene BM25 稀疏）接入后新增 `embedding` / `reranker` /
> `vectorstore` / `app.rag.*` 漏斗旋钮；`app.rag.top-k` 已退役（由 `rerank-top-n` 承担注入上限语义）；
> `llm` 为多 provider 主备结构（`llm.providers`，首位主、其余备）。

```yaml
# dataId: Agentdemo007.yaml    group: DEFAULT_GROUP    格式: yaml

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

# ---- LLM 主备容灾（首位=主，其余=备；${} 占位经服务器环境变量解析，不落明文）----
llm:
  enabled: true
  providers:
    - id: siliconflow
      base-url: https://api.siliconflow.cn/v1
      api-key: ${SF_KEY}
      large-model: Qwen/Qwen3.5-35B-A3B
      small-model: Qwen/Qwen3.5-27B
      disable-thinking-params:
        enable_thinking: false
    - id: aliyun
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: ${ALIYUN_KEY}
      large-model: qwen3.8-max
      small-model: deepseek-v4-pro-0813
      disable-thinking-params:
        enable_thinking: false

# ---- 真实 RAG·稠密嵌入（模型必须与 Python 入库一致，否则向量空间错位）----
embedding:
  enabled: true
  providers:
    - id: siliconflow
      base-url: https://api.siliconflow.cn/v1
      api-key: ${SF_KEY}
      model: Qwen/Qwen3-Embedding-8B

# ---- 真实 RAG·远程重排（全失败降级本地 BM25，只改顺序安全）----
reranker:
  enabled: true
  providers:
    - id: siliconflow
      base-url: https://api.siliconflow.cn/v1
      api-key: ${SF_KEY}
      model: Qwen/Qwen3-Reranker-8B

# ---- 真实 RAG·Chroma 真库（稀疏通道为 Lucene 磁盘倒排，启动自动从 Chroma 流式同步）----
vectorstore:
  type: chroma
  chroma:
    base-url: http://127.0.0.1:8001   # 同机部署走 localhost；远程填 http://<host>:8001
    collection: kb_customer_service
    # api-key: <token>                 # 自建无鉴权留空；开启鉴权时填 Bearer token

app:
  rabbitmq.enabled: true
  pipeline.mode: linear
  rag:
    recall-dense: 24                  # 生产大库调 50+
    recall-sparse: 24
    prefilter-min-score: 0.2          # 重排前宽松粗滤，0=关闭
    rerank-top-n: 10                  # 重排 top_n = 注入上限（池 ≤ top_n 自动跳过重排）
    min-score: 0.4                    # 真嵌入口径（dev hash 词袋才是 0.3）
    rerank-min-score: 0.3
    min-count: 1
    lucene-dir: /var/lib/agentdemo007/lucene-bm25   # 建议持久盘绝对路径，重启增量对齐
    seed.enabled: false               # 真库模式双保险（vectorstore.type=chroma 已自动禁种子）

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

**访问闸口独立 dataId（2026-09-18，统一旋钮·秒级热更新）**：

```json
// dataId: agentdemo-gate.json    group: DEFAULT_GROUP    格式: json
{
  "enabled": true,                  // 统一旋钮：true = ①/chat 需口令 ②外部 IP 日额 ③/eval 仅本机；false = 全开放
  "accessCode": "<你的访问口令>",     // 只放 Nacos，勿入仓库/环境变量明文
  "dailyLimit": 5,                  // 每外部 IP 每自然日对话轮数（localhost 豁免；Asia/Shanghai 日界）
  "requiredMessage": "本站为作品集演示站点，请输入访问口令（见简历附注）",
  "exhaustedMessage": "今日体验额度已用完（每 IP 每日 5 轮），欢迎明日再访"
}
```

字段可部分更新（省略/null 沿用当前值）；解析失败保留旧值；Nacos 不可达降级 yml 本地兜底值。配额存储：`REDIS_ENABLED=true` → Redis INCR+48h TTL（重启不丢）；否则内存重启清零。**发布清单**：Nacos 建 `agentdemo-gate.json` → 确认 `REDIS_ENABLED=true` → nginx `proxy_set_header X-Real-IP $remote_addr`（IP 防伪信任链前提）→ 安全组只放 80/443（8080 直连绕过闸门）。

### 6.6 启动命令

```bash
export SPRING_PROFILES_ACTIVE=prod
export NACOS_SERVER_ADDR=nacos.internal:8848
export NACOS_NAMESPACE=<namespace-id>        # 留空 = public
export NACOS_USERNAME=<nacos-user>           # 服务器 Nacos 开鉴权才需要
export NACOS_PASSWORD=<nacos-password>
export SF_KEY=<siliconflow-api-key>          # dataId 里 ${SF_KEY} 占位的实参（ALIYUN_KEY 等同理）
java -jar target/Agentdemo007-0.0.1-SNAPSHOT.jar
```

### 6.7 验证与排查

```bash
# 1) 启动日志确认加载（组件真实日志串，已核对 2025.1.0.0 字节码）
grep -E "\[Nacos Config\] Load config|is empty|Listening config" /var/log/agentdemo007/*.log | tail

# 2) 健康
curl -i http://localhost:8080/actuator/health

# 3) 热更新事件：改 dataId 任意键，数秒内应看到 Refresh 事件日志
#    ⚠ 见 6.1 热更新边界：重绑只对运行期读取的键生效，多数键需重启
grep -E "NacosConfigRefreshEvent|Refresh" /var/log/agentdemo007/*.log | tail
```

预期：启动日志出现 `[Nacos Config] Load config[dataId=Agentdemo007.yaml, group=DEFAULT_GROUP] success` + `[Nacos Config] Listening config: dataId=Agentdemo007.yaml`；Nacos 不可达时 `optional:` 跳过、走本地配置启动不阻塞。

**配置没生效时：**

| 现象 | 含义 / 处理 |
|------|-------------|
| 无 `Load config ... success` 日志（被 `optional:` 跳过） | 连接失败，或 **namespace 填了显示名而非 ID**（最常见）、安全组未放行 8848 → 修 6.4 连接参数；期间业务配置走本地默认，不阻塞启动 |
| `[Nacos Config] config[...] is empty` | dataId 内容为空 → 粘贴 6.5 示例后发布 |
| 启动失败，异常栈见 YAML 解析错 | dataId 内容语法错 → 修 Nacos 上的 `Agentdemo007.yaml` 后重启（ConfigData 解析失败是致命的，不会只跳过坏的那一行） |
| 改了 dataId 某些键没生效 | 热更新重绑仅对运行期读取的键生效（见 6.1 热更新边界）；构造期装配键改后需**重启**。另查键名陷阱（§6.5 下方） |

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

**没有 `@RefreshScope`**（需 `spring-cloud-context`，不在依赖里）。组件内置机制（已核对 2025.1.0.0 字节码）：dataId 变更 → `NacosConfigRefreshEvent` → 组件把该 dataId 的 property source **原位 `replace` 进 Environment** —— 仅实时读取方随后生效，任何 bean 都不重建、不重绑。

| 读取方式 | 热更新是否生效 |
|----------|----------------|
| 每次调用走 `Environment.getProperty(...)` | ✅ 实时 |
| `@Value("${...}")` 注入 bean 字段 / 构造参数 | ❌ 需重启 |
| `@Bean` 工厂方法消费 properties（如 `IntentConfig.ruleMatcher(props)`、redis / chroma 客户端装配） | ❌ 重绑不重建 bean |
| `@ConfigurationProperties` bean | ❌（无 spring-cloud-context 重绑器，构造期绑定后不再刷新） |

**结论**：本项目消费方式绝大多数落 ❌ 侧 —— 生产改配置（`llm.providers`、RAG 旋钮、`vectorstore.type` 等）**以重启为准**。改完没生效，先确认消费者的读取方式 —— 这不是 bug。

### 6.10 Nacos 3.x / compose 注意事项

- **端口冲突**：`docker-compose.yml` 中 Nacos 容器同时映射了 `8080:8080`（Nacos 控制台），与应用 `server.port=8080` 冲突。容器部署时改应用端口，或去掉 Nacos 的 8080 映射
- **Nacos 自身需要 MySQL**：compose 用 `SPRING_DATASOURCE_PLATFORM=mysql` + `MYSQL_SERVICE_DB_NAME=nacos_config`，需先在 MySQL 建 `nacos_config` 库
- **鉴权已开启**：`NACOS_AUTH_TOKEN` / `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE`。客户端凭据走 `NACOS_USERNAME` / `NACOS_PASSWORD`（§5.5，为空不传）—— 内网部署可留空；**暴露公网前必须补鉴权**
- **compose 里的密码是明文示例**（`MYSQL_SERVICE_PASSWORD` 等），生产必须替换

### 6.11 安全说明

**约定已闭环**：本项目约定「密钥经环境变量注入，配置文件仅 `${}` 占位，不落明文」—— dataId 里只有 `${SF_KEY}` / `${ALIYUN_KEY}` **占位符**，运行期从服务器环境变量解析（env 变量优先级高于 config data，见 6.1）。密钥明文既不落仓库也不落 Nacos。

残留风险与缓解：

- ✅ Nacos 鉴权开启（`NACOS_AUTH_TOKEN`）
- ✅ dataId 权限收敛到配置管理角色
- ⚠ dataId 内非密钥项（数据库/Redis 口令等）仍以明文存于 Nacos 存储层（MySQL `nacos_config`）—— 这类连接串建议也走 env 占位（`DS_URL` / `DS_PASSWORD` 已内置）
- ⚠ **`ADMIN_TOKEN` / `EVAL_TOKEN` 留在环境变量**；敏感项如确需进 Nacos，先接 Vault 类方案

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
- **五张表**：`chat_turn`（会话轮次）、`audit_event`（审计事件）、`hitl_checkpoint`（HITL 挂起检查点持久副本）、`hitl_ticket`（HITL 人工工单持久副本）、`biz_order`（业务订单，供 HITL 业务前置校验），逐字段对齐 JPA 实体 `@Column`。
- **dev**：H2 走 `ddl-auto=update` 自建表，`mode=embedded` 执行 `schema.sql` 时表已存在 → 跳过，零冲突。

> 项目未集成 Flyway/Liquibase。结构演进时：改 `schema.sql`（加 `IF NOT EXISTS` 幂等语句；勿加 `ALTER`/`INSERT` 这类非幂等语句），或手动 DBA 执行迁移。引入迁移工具后此节作废。
>
> **推荐索引**（脚本注释列出，未纳入 always 幂等脚本，因 MySQL 不支持 `CREATE INDEX IF NOT EXISTS`）：`chat_turn(session_id, turn_timestamp)`、`audit_event(trace_id)`、`audit_event(created_at)`、`hitl_ticket(idempotency_key)`、`hitl_ticket(status)`、`hitl_checkpoint(idempotency_key)`。首次建表后手动执行。

### 8.1 HITL L2 挂起-恢复：三表 DDL + mock 数据（用户自建）

执行 `mysql -h <host> -u <user> -p agent_dev < docs/sql/hitl_l2_init.sql`（可重复执行：建表 `IF NOT EXISTS`，mock 数据先 DELETE 后 INSERT 幂等）。包含：

- `hitl_checkpoint`：挂起检查点 **DB 持久真相源**；Redis 热副本（`hitl:checkpoint:{ticket_id}`，TTL `HITL_CHECKPOINT_REDIS_TTL` 默认 24h，`REDIS_ENABLED=true` 时自动双写）另存一份防重启丢失。Redis/DB 写入均异步（单守护线程 `hitl-checkpoint-writer`），不阻塞主线。
- `hitl_ticket`：人工工单持久副本（异步落库 `hitl-ticket-writer`），重启后按 id / 幂等键 / PENDING 列表回源——**审批不丢单**。
- `biz_order`：业务订单（**必须配数据**，无数据=审批 fail-closed 拒绝）。工单收尾不自证：退款动作对账 `payment_status`（仅 `PAID` 放行），退货动作对账 `logistics_status`（已签收放行）；订单不存在/查询失败 → 409 拒绝，修复后重新 confirm 或走显式恢复接口。

恢复链路防重：检查点幂等键 ≠ 工单幂等键 → 检查点作废不产出（允许多工单并存，各消费自身键）；恢复前 CAS 消费检查点防重复提交；`POST /admin/hitl/tickets/{id}/resume` 为显式恢复接口（APPROVED 单重驱，业务校验修复后重试）。

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
| 对话返回话术而非回复 | `LLM_ENABLED` 未开或 dataId `llm.providers` 未配 → LLM 降级；配置后恢复 |
| 管理台 401 | `ADMIN_TOKEN` 不匹配 → `/#/auth` 录入正确令牌 |
| dev 不连 RabbitMQ 正常 | `RABBITMQ_ENABLED` 默认 false，NoopMessagePublisher，会话/审计仅记 log |
| `npx vue-tsc` 报 `ERR_PACKAGE_PATH_NOT_EXPORTED` | npx 缓存与 TS6 不兼容 → 用 `npm run build` / `npm run typecheck`（本地 `node_modules/.bin/vue-tsc`） |
| 多实例横向扩容 | HITL 工单/检查点/售后提交登记按**单实例内存真相源**设计（DB/Redis 为异步副本）；扩容前需把检查点消费 CAS 与工单状态机挪到 DB 乐观锁，否则各实例状态漂移 |
| 闸口对直连 8080 的请求不设防 | localhost 豁免与 IP 解析的信任链以「nginx 在前」为前提（X-Real-IP 由 nginx 覆写）→ 8080 只对内、安全组仅放 80/443 |

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
  -e NACOS_SERVER_ADDR=... -e NACOS_NAMESPACE=... \
  -e LLM_ENABLED=true -e SF_KEY=... \
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
NACOS_SERVER_ADDR=... NACOS_NAMESPACE=... \
LLM_ENABLED=true SF_KEY=... \
java -jar target/Agentdemo007-0.0.1-SNAPSHOT.jar

# 验证
curl localhost:8080/actuator/health
curl -H "X-Admin-Token: $ADMIN_TOKEN" localhost:8080/api/obs/summary
```
