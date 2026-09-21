# 客服知识库 RAG 前置：多格式文档「清洗-切分-入库」实战教程

> 配套工具：[scripts/rag-ingest/](../../scripts/rag-ingest/)（纯 Python 参考流水线，不依赖 LlamaIndex）
> 语料位置：[src/main/resources/corpus/](../../src/main/resources/corpus/)（小哲电商客服知识库，23 个文件＝20 篇 markdown + 3 个活动规则 JSON，灌库后 156 块）
> 目标向量库：Chroma（默认，docker-compose 已含容器）/ Milvus（standalone）
> 后续衔接：本教程产出的 collection 将作为 Java 侧（Agentdemo007）真实 RAG 检索的数据源

---

## 0. 为什么「按 chunk_size 硬切」是反模式

很多教程的第一步是"每 500 字切一刀"。对客服知识库这么做会出三类事故：

| 反例 | 硬切的后果 | 正确做法 |
|------|-----------|---------|
| **表格被拆散** | 「新疆运费 15 元」在块 1，「偏远加收 10 元」的表头在块 0——列语义断裂，检索召回的半张表无法回答问题 | 表格序列化为 markdown 后**整表一块**（原子块），超长再按行分块且**每块重复表头** |
| **语义句中截断** | 「退款按原支付渠道 3-7 个工作日到账」被切成「…3-7 个」+「工作日到账」，两个块单独看都语义残缺 | 先按标题/句子边界切，chunk_size 只做兜底上限 |
| **图文失联** | 图片与它的图注/上下文被切到不同块，图片块成了噪声 | 图片 OCR 成文本或生成「占位+图注」块，与上下文挂接 |

所以本方案的流水线是四层：**分型解析 → 清洗 → 分层切分 → 统一落库**。chunk_size 只是最后一道兜底约束，不是切分的主要依据。

---

## 1. 十类文档分型矩阵（核心速查）

| # | 文档类型 | 解析工具 | 清洗要点 | 切分策略 | 落库元数据 |
|---|---------|---------|---------|---------|-----------|
| 1 | **md / txt**（LLM 友好） | 直接读 | NFKC 规范化、空白折叠 | `structure`：按 `#` 标题层级，每小节一块 | source / section / doc_no / domain / temporal_tag |
| 2 | **html** | BeautifulSoup | 去 script/style/nav/aside | 转 markdown 后走 `structure` | 同上 + source 保留页面路径 |
| 3 | **docx**（Word） | python-docx | 标题样式（Heading N/标题 N）→ 层级；表格转 md | `structure` + **表格原子块** | 同上（updated 取文档属性） |
| 4 | **pdf 文本型** | PyMuPDF + pdfplumber | **页眉页脚去重**（重复行占比 ≥60% 剔除）、乱码剔除 | 加粗短行判标题 → `structure`；表格原子块 | source / **page** / section |
| 5 | **pdf 扫描型** | 文本密度检测 + PaddleOCR（可选） | 整页 OCR 结果再走通用清洗 | `semantic` 或 `structure`（OCR 出标题行时） | source / page / doc_type=`pdf_ocr` |
| 6 | **xlsx / xls**（Excel） | openpyxl | 表头拼进行内（保持列语义） | 小表整表一块；**大表按 50 行分块、每块重复表头** | source / **sheet** / kind=`table` |
| 7 | **csv / tsv** | 内置 csv | 同 xlsx | 同 xlsx | 同 xlsx |
| 8 | **json**（活动规则等结构化数据） | 内置 json | 键名中文化、列表分号拼接 | **每条记录一个原子块**，不与相邻内容合并 | source / doc_no / domain / **valid_from / valid_until**（时间窗口） |
| 9 | **纯图片**（jpg/png 截图/拍照件） | PIL + PaddleOCR（可选） | OCR 文本走通用清洗 | 整图一块（OCR 文本少，天然原子） | source / doc_type=`image_ocr` |
| 10 | **嵌套在 docx/pdf 中的表格** | 同宿主文档 | 竖线转义、None 补空 | 永不跨块拆表 | kind=`table` |

两个工程铁律：

1. **每类文档都有"兜底出路"**：扫描页没装 OCR → 输出占位块（`【扫描页】请人工补录`）而不是空过；图片识别不出 → 占位元数据块。宁可留下"待补录"标记，也不让内容静默丢失。
2. **元数据跟着块走**：每一块都带 `source/doc_type/page/sheet/section`，检索命中后能回答"这条政策出自哪个文件第几页"——这是客服场景引用可溯的底线。

---

## 2. 语料编写约定（markdown 类）

`src/main/resources/corpus/` 下的 markdown 文档遵循统一约定：

```markdown
# 退款政策

> 文档编号：KB-REFUND ｜ 知识域：after_sale_policy ｜ 更新日期：2026-09-15 ｜ 时效标签：CURRENT

## 退款到账时效

退款按原支付渠道原路退回，一般 3-7 个工作日到账……
```

- **首行引用块 = 机读元数据**：摄入脚本解析出 `doc_no / domain / updated / temporal_tag`，逐块写入向量库
- **`##` 小节 = 结构切分单元**：一个小节一块；**FAQ 类每条 `## Q：…` 一问一答原子块**（问法即检索锚点，客服场景命中率最高）
- **时效标签**：`CURRENT`（现行）/ `HISTORICAL`（历史存档）。历史政策（如 `09-historical-policies.md` 里已废止的"15 日无理由退货"）会被 Java 侧时效治理隔离标注为"历史参考资料"，不冒充现行政策

### 现有语料清单（23 个文件 = 156 块）

| 文件 | 知识域 | 内容 |
|------|--------|------|
| 01-refund-policy | after_sale_policy | 退款范围/30 天窗口/3-7 工作日到账/金额规则/大额审批 |
| 02-return-policy | received_return_policy | 7 天无理由/完好标准/退货流程/运费 |
| 03-shipping-logistics | logistics_policy | 48h 发货/3-5 日送达/包邮/异常件 |
| 04-member-promotion | promotion_and_member_policy | 银卡满千/金卡满万/权益/优惠券 |
| 05-invoice | invoice_service | 电子普票/增值税专票六要素/换开红冲 |
| 06-order-account | order_account | 订单状态/ORD 号/归属隐私 |
| 07-payment-security | payment_security | 支付/重复扣款/原路退回/防诈 |
| 08-human-service | human_service | 转人工场景/服务时间/投诉时效 |
| 09-historical-policies | **HISTORICAL** | 2024-06-30 前旧规归档 |
| 10-faq-common / 11-faq-after-sale | faq | 33 条高频问答（含"增值税专票怎么开"等真实问法） |
| 12-price-protection | promotion_and_member_policy | 价保 7 天/补差/不适用情形 |
| 13-warranty-service | after_sale_policy | 三包（7 日退 15 日换）/保修期/寄修 |
| 14-gift-card | payment_security | 礼品卡面值/36 个月有效期/退款回卡 |
| 15-virtual-goods | order_account | 话费会员直充/不退限制/到账异常 |
| 16-fresh-coldchain | after_sale_policy | 冷链 1-3 日达/坏单包赔/不支持无理由 |
| 17-large-appliance | logistics_policy | 送装一体/上楼费/以旧换新 |
| 18-b2b-enterprise | order_account | 企业认证/对公转账/专票 |
| 19-cross-border | cross_border_policy | 清关 5-10 日/综合税/不支持无理由 |
| 20-faq-services | faq | 15 条增值服务问答（价保/三包/礼品卡/生鲜/跨境…） |
| **21-activity-rules-2026q3.json** | promotion_and_member_policy | Q3 活动 7 条（时间态：已结束 3 + 进行中 2 + 未开始 2） |
| **22-activity-rules-2026q4.json** | promotion_and_member_policy | Q4 大促 5 条（双11/黑五/双12/年终，全部未开始） |
| **23-promo-mechanics.json** | promotion_and_member_policy | 玩法机制 3 条（秒杀/预售/叠加总则，全年有效） |

### 活动规则 JSON：时间约束语料（检验系统时效效果的关键）

活动规则天然带时间窗口，是检验 RAG 时效治理的最佳语料。约定结构：

```json
{
  "doc_no": "KB-ACT-Q3", "domain": "promotion_and_member_policy",
  "updated": "2026-09-15", "temporal_tag": "CURRENT",
  "activities": [
    {
      "id": "ACT-2026-095",
      "name": "秋季数码焕新季", "type": "以旧换新+满减",
      "time": { "start": "2026-09-20", "end": "2026-10-07" },
      "rules": ["手机以旧换新补贴最高 500 元", "每满 999 减 100"],
      "superposition": "补贴可与满减叠加，不与优惠券叠加",
      "restrictions": ["活动商品不参与七天无理由退货（质量问题 30 天内可退）"]
    }
  ]
}
```

- **每条活动 = 一个原子块**：`name/type/time/rules/restrictions/superposition/member_levels/notes` 序列化为可检索文本，未识别字段兜底输出不丢信息
- **`time.start/end` → 元数据 `valid_from/valid_until`**：这是与正文日期不同的**机读时间约束**，Java 侧检索命中后可直接比较"当前时间是否落在窗口内"，不需要从正文里再抠日期
- **时间态刻意铺开**（以 2026-09-15 为基准）：已结束（暑期冰饮季 7/01-8/31、开学季、中秋礼遇）、进行中（九月会员日 9/09-9/15、家居焕新周 9/15-9/30）、未开始（秋季数码 9/20 起、Q4 全部大促）——灌库后问「现在有什么活动」「中秋活动还能参加吗」「双11 什么时候开始」即可检验三种时间态的回答质量
- 未识别为 `time` 的时间信息（如返券"12-20 前有效"写在 rules 文本里）仍靠正文语义兜底，两种机制互补

> 口径纪律：语料中的数字与代码严格一致——7 天退货窗口（`ReturnValidationRule`）、30 天退款窗口（`RefundValidationRule`）、3-7 个工作日到账与 gold 会员权益（`MockPolicyQueryService`）、演示账户 10086=金卡、活动商品不参与无理由退货。改政策时**先改语料再改代码**（或反之），两边永远不能打架。

---

## 3. 切分策略详解与选型

### 3.1 structure —— 按标题层级（默认，结构化文档首选）

- 遇 `#` 标题就开新节，正文累积到当前节；节文本**带标题路径前缀**再嵌入（如「退款政策 / 退款到账时效\n正文…」），标题本身是最强的检索信号
- 表格块永远独立成块，并附上所属标题做上下文
- 无任何标题的文档（txt）自动降级为硬约束切分
- FAQ 文件每条 `## Q：…` 天然一问一答一块——**这是客服语料的最佳粒度**

### 3.2 semantic —— 句子嵌入断点（无标题长文本）

对 txt、扫描 OCR 输出这类没有标题结构的文本：

1. 按中文句号类标点切句
2. 逐句嵌入（SiliconFlow Qwen3-Embedding-8B）
3. 相邻句余弦相似度 **< 阈值（默认 0.55，越大切得越碎）** 即断开，且段内累计 ≥60 字才允许断
4. 每段再过一遍硬约束兜底

代价：切分阶段就要调嵌入 API（每句一次）。**有标题结构的文档没必要用**——标题已经免费告诉你语义边界在哪。

### 3.3 parent-child —— 小块检索，大块返回（可选开关 `--parent-child`）

RAG 的经典两难：块切小→向量精度高但上下文残缺；块切大→上下文完整但向量模糊。parent-child 的解法：

- 超 800 字的节拆成 ≤400 字的**子块**（用于嵌入与检索）
- 每个子块的元数据挂 `parent_text` = 父节全文（检索命中后把父节喂给大模型）

```
父节「退款政策 / 退款审批流程」（800 字）
├── 子块 1（400 字，parent_text=父节全文） ← 命中的是这个
└── 子块 2（400 字，parent_text=父节全文） ← 命中的是这个
                                     ↓
                     实际送入大模型上下文的是 800 字父节
```

> 生产化提示：`parent_text` 放元数据便于演示，量大后应外挂文档库（按 parent_id 取父节），向量库只存子块。

### 3.4 硬约束兜底

任何策略产出的块最后都过一遍 `max-chars`（默认 600 字 ≈ 512 token）+ `overlap`（默认 80 字，句子边界回滚重叠）。它是安全网，不是主切分器。

**选型速查**：

| 文档特征 | 推荐策略 |
|---------|---------|
| 有标题（md/docx/HTML/带标题 PDF）——本项目语料全部属于此类 | `structure`（默认） |
| 无标题长文（txt、扫描 OCR 输出） | `semantic` |
| 长政策节需要完整上下文 | `structure` + `--parent-child` |
| 表格 | 永远原子块（两种策略内置同样处理） |

---

## 4. 扫描件与纯图片：OCR 选型

| 工具 | 强项 | 弱项 | 适用 |
|------|------|------|------|
| **PaddleOCR**（本流水线默认，可选依赖） | 中文效果第一梯队、轻量、离线 | 复杂表格结构还原弱 | 扫描页、拍照单据、截图 |
| **MinerU** | PDF 版面还原/表格/公式强（OpenDataLab 出品） | 部署较重（含模型下载） | 复杂版式 PDF 整体转 markdown |
| **unstructured** | 生态全（partition_docx/pdf 自动分型） | 抽象层厚、依赖多 | 想要"开箱即用"不想自己写 reader |
| **云 OCR**（阿里/腾讯/百度） | 免调优、稳定 | 计费、文档出域 | 有合规许可的生产环境 |

本项目流水线的 OCR 策略：**可选依赖 + 优雅降级**。装了 PaddleOCR → 扫描页/图片自动 OCR（`doc_type=pdf_ocr/image_ocr`）；没装 → 占位块告警，其余类型照常入库。OCR 结果再走同一套清洗（规范化/去重/脱敏），不因为是 OCR 文本就绕过质检。

---

## 5. 环境准备与分步命令

### 5.1 起向量库

**方式 A：本机 docker（开发联调）**

```bash
# Chroma（docker-compose.yml 已含 chroma:1.0.8，端口 8000）
docker compose up -d chroma

# 或 Milvus standalone（端口 19530；单容器内嵌 etcd）
docker run -d --name milvus-standalone -p 19530:19530 -p 9091:9091 \
  -e ETCD_USE_EMBED=true -e ETCD_DATA_DIR=/var/lib/milvus/etcd \
  -e COMMON_STORAGETYPE=local \
  -v $(pwd)/data/milvus:/var/lib/milvus \
  milvusdb/milvus:v2.4.15 milvus run standalone
# 版本以 Milvus 官方文档为准；也可用其 standaloneEmbed 下游 compose
```

**方式 B：连接远程服务器上已部署好的 Chroma**

```bash
# 1) 连通性检查（Chroma 1.0.x 心跳端点，返回 {"nanosecond heartbeat":...} 即通）
curl http://<服务器IP>:8000/api/v2/heartbeat
# 老版本端点为 /api/v1/heartbeat；不通先查云安全组/防火墙是否放行 8000

# 2) 服务器只对本机开放时，用 SSH 隧道转回本地
ssh -L 8000:localhost:8000 <user>@<服务器IP>     # 保持挂起，另开终端
# 之后 --uri 用 http://localhost:8000 即可

# 3) 语料入库指向远程（--uri 传服务器地址）
python ingest.py --input ../../src/main/resources/corpus \
  --store chroma --uri http://<服务器IP>:8000

# 4) 服务端开了 token 鉴权（TokenAuthentication）时
export CHROMA_TOKEN=<服务端配置的 token>
# https 端点直接写 --uri https://<域名>:443，脚本自动切 ssl
```

> 服务器上的 Chroma 就是后续 Java 侧的目标库：此处 `--uri` 的地址将来填进后端配置 `VECTOR_STORE_URL`，collection（`kb_customer_service`）两边必须同名。

### 5.2 Python 环境

```bash
cd scripts/rag-ingest
python3 -m venv .venv && source .venv/bin/activate   # Python ≥ 3.10；一律用 python3
pip install -r requirements.txt
hash -r    # zsh 用 rehash：清 shell 命令缓存，防止 python 命令残留指向系统旧版本
# 可选：启用扫描件/图片 OCR
# pip install paddleocr paddlepaddle
```

> **⚠️ 多 Python 共存机器的真实事故**（macOS）：机器上有系统 3.9 与 3.13 并存，venv 由 `python3`（3.13）创建，但 shell 里裸 `python` 命令仍被 3.9 框架抢占——于是 `pip` 把依赖装进 3.13 的 venv、`python ingest.py` 却用 3.9 跑，直到最后一步才 `ModuleNotFoundError: No module named 'chromadb'`，且 `pip show` 显示"已安装"（元数据在、解释器不对）。**规约：本项目所有命令一律 `python3`**；ingest.py 入口已内置环境守卫，检测到解释器不在 .venv 中会直接拒绝并给出修复指引。

### 5.3 嵌入 API key

嵌入用 SiliconFlow 的 `Qwen/Qwen3-Embedding-8B`（OpenAI 兼容端点，4096 维）——**必须与 Java 后端 `EmbeddingService` 同模型**（原因见第 7 节）。

```bash
export SF_KEY=<你的 SiliconFlow API key>       # 脚本默认读这个环境变量
```

---

## 6. 端到端演示

```bash
# ① 官方语料（20 篇 md + 3 个活动规则 JSON）入库（Chroma）
python ingest.py --input ../../src/main/resources/corpus --store chroma
# 输出示例：
#   [parse] KB-REFUND              type=md       temporal=CURRENT    chunks=6  {'text': 6}
#   [parse] KB-FAQ-AFTERSALE       type=md       temporal=CURRENT    chunks=17 {'text': 17}
#   [parse] KB-HISTORY             type=md       temporal=HISTORICAL chunks=4  {'text': 4}
#   [parse] KB-ACT-Q3              type=json     temporal=CURRENT    chunks=7  {'text': 7}
#   [chunk] 共 156 块（表格原子块 0，历史块 4）
#   [chunk] 按文档类型：{'md': 141, 'json': 15}
#   [write] 落库完成，库内总量：156 块
#   [verify] 语义召回自检（top1）：
#     Q：退款多久到账
#       → [01-refund-policy.md · 退款到账时效] 退款按原支付渠道原路退回…

# ② 生成多格式样例并混合入库（docx/pdf/xlsx/png/html/json 六类同跑）
python make_samples.py
python ingest.py --input ./samples --store chroma

# ③ Milvus 换库重灌
python ingest.py --input ../../src/main/resources/corpus --store milvus

# ④ 调参：只解析切分看统计，不调 API
python ingest.py --input ../../src/main/resources/corpus --dry-run
python ingest.py --input ./samples --parent-child --dry-run   # 开 parent-child 对比块数变化
```

**验证清单**（每轮灌库后过一遍）：

1. **数量核对**：`[write] 库内总量` 与 `[chunk] 共 N 块` 一致（重跑幂等，数量不翻倍）
2. **类型分布**：`[chunk] 按文档类型` 里 md/docx/pdf/xlsx/png/json 都有块；pdf 扫描页应有 `pdf_ocr` 或占位块
3. **召回抽查**：脚本自带 7 题自检（退款时效/无理由起算/专票六要素/包邮门槛/会员升级/**现在有什么活动/双11 何时开始**），top1 应命中对应文档
4. **时效隔离**：问「旧退货政策」命中 `09-historical-policies.md`，其块 `temporal_tag=HISTORICAL`——Java 侧将据此标注「历史参考资料」
5. **活动时间态**（JSON 语料专属）：问「现在有什么满减活动」应命中 valid_until 未过期的进行中活动（九月会员日/家居焕新周）；问「中秋活动」命中的块 `valid_until=2026-09-12` 已过期——Java 侧据此提示「该活动已结束」；问「双11」命中未开始的 Q4 块（`valid_from=2026-11-01`）
6. **表格完整性**：问「偏远地区运费」命中 xlsx 运费表块，回答能看到完整列关系

---

## 7. 与 Java 侧（Agentdemo007）的对齐约定

本 collection 的最终消费方是 Java 后端的真实 RAG 检索。以下四条是**硬约定**，任何一条被破坏都会导致检索静默劣化：

### 7.1 向量空间一致性（最重要）

嵌入模型必须与后端 `EmbeddingService` 同源：`Qwen/Qwen3-Embedding-8B`，4096 维。换模型 = 换向量空间，查询向量与库内向量不可比，**必须 drop collection 全库重灌**。Milvus 侧脚本已内置维度校验（不匹配直接拒绝写入）；Chroma 侧请在换模型时手动删除 collection。

### 7.2 collection 与元数据契约

- collection 名：`kb_customer_service`（可通过 `--collection` 调整，Java 侧配置需同步）
- 每块元数据 → Java 侧 `RagFragment` 字段对应：

| 向量库 metadata | 含义 | Java 侧对应 |
|-----------------|------|------------|
| `text`（document 字段） | 块正文 | `RagFragment.text` |
| `source` | 相对文件路径 | `RagFragment.source`（引用展示） |
| `section` / `page` / `sheet` | 定位信息 | 引用溯源（"出自某文件第几页"） |
| `doc_type` | md/docx/pdf/pdf_ocr/xlsx/csv/image_ocr… | 观测与统计 |
| `temporal_tag` | CURRENT / HISTORICAL | 时效隔离：HISTORICAL 块 `displayText()` 加「历史参考资料·截至 validUntil」前缀 |
| `valid_from` / `valid_until` | 活动时间窗口（JSON 语料） | 检索后比较当前时间 → 判断活动进行中/已结束/未开始，不依赖正文抠日期 |
| `doc_no` / `domain` | 文档编号 / 知识域 | RoutePlan 知识域过滤（after_sale_policy / received_return_policy / promotion_and_member_policy…） |
| `kind` | text / table / figure | 表格块完整性保护 |
| `parent_text` | 父节全文（parent-child 模式） | 命中子块后回填父节进上下文 |

### 7.3 检索链路衔接点（已落地）

Java 侧真实 RAG 已接（`vectorstore.type=chroma` 属性门控切换）：

- `ChromaVectorStore` 实现 `VectorStore` seam（稠密通道）：score = 1 − cosine 距离，metadata 契约逐键映射（source/valid_until→validUntil/temporal_tag→temporalTag/domain），4096 维守卫
- 稀疏通道 = **Lucene 磁盘倒排 BM25**（`LuceneBm25IndexService`，`lucene-core + lucene-analysis-common 9.12.2`）：Java 启动时从 Chroma **分页流式**拉取全量文档（常驻内存 O(1) 页）写入磁盘索引（MMap，堆内存极小），确定性 chunk id 与稠密库一致——双索引 ID 天然统一，增量同步 + 陈旧清理。⚠️ 不在 JVM 常驻语料全文（内存考量），也不要指望 Chroma 原生稀疏（见 FAQ）
- 检索漏斗：宽召回（`RAG_RECALL_DENSE/SPARSE`）→ knowledgeDomains 窄化 → 宽松粗滤 → 重排（池 ≤ top_n 跳过）→ 置信度终闸（被远程重排的看 relevance ≥ `RAG_RERANK_MIN_SCORE`，未重排的看 cosine ≥ `RAG_MIN_SCORE`；BM25-only 候选未经理裁决不得入上下文）
- `PolicyQueryService.query(domain, query)` seam → `RagPolicyQueryService`（真库模式）以用户问题为检索词委托同一漏斗，3 个政策 @Tool 加 query 参数透传问题原词

### 7.4 灌库与重建纪律

- **重跑幂等**：确定性 id（sha256(source#section#text)），重复执行是覆盖不是翻倍
- **改语料后必须重灌**：markdown 编辑后重跑 `ingest.py` 即可
- **换嵌入模型必须 drop 重建**：没有"增量换模型"这回事
- **语料与代码同步**：政策数字改动时，corpus 语料与 `MockPolicyQueryService`/ValidationRule 的窗口常量要么同 commit 改，要么先改语料（语料是客服对外口径的事实源）

---

## 8. FAQ

**Q：明明装了 chromadb，运行却 ModuleNotFoundError，`pip show` 还说已安装？**
九成是 **pip 和 python 不同源**：多 Python 共存的机器上，`pip` 落在 venv（3.13）里、裸 `python` 命令被系统旧版本（3.9）抢占。自检：`python --version` vs `python3 --version` 是否一致。**规约：一律用 `python3`**；激活 venv 后 `hash -r`（zsh：`rehash`）清 shell 命令缓存；脚本入口的环境守卫也会直接拦截并指路。如果 `pip show chromadb` 显示已装但同解释器 import 仍失败，是安装损坏，`pip install --force-reinstall --no-cache-dir chromadb`。

**Q：改了语料（删了文档/小节）重灌后，库里数量没变少？**
upsert 只覆盖不删除——删减语料后旧块会以孤儿形式留在库里。加 `--rebuild` 先清空重建 collection 再灌（语料是唯一事实源，库是纯派生物，整库重建零风险，缓存保证 0 API 重嵌）。

**Q：重跑会不会重复烧嵌入配额？**
不会。嵌入缓存按 sha256(model+文本) 落盘（`embedding-cache.json`）：语料没变的块直接命中缓存 0 API 调用，只有新增/变更的块才真嵌。缓存文件已 gitignore（可再生）。语料完全没变时甚至不需要配 SF_KEY。

**Q：维度不匹配报错？**
Milvus 侧脚本直接拒绝；Chroma 侧表现为检索质量骤降。原因 99% 是换了嵌入模型——drop collection 全库重灌。

**Q：嵌入批次报 429/限流？**
SiliconFlow 免费档限速。脚本已内置指数退避重试；仍频繁触发就把 `--batch-size` 调到 8 或 4。

**Q：PaddleOCR 装不上/太慢？**
不装也能跑通全流程：扫描页与图片输出占位块（带"请人工补录"标记），其余 8 类不受影响。OCR 引擎首次加载要下模型（数百 MB），进程内已缓存，同进程多次调用不再重复加载。

**Q：PDF 页眉页脚没去干净/误删正文？**
去重比例默认 60%（出现在 ≥60% 页面的行视为页眉页脚）。水印型页眉每页略有差异时去不掉（属正常，余量靠检索阈值消化）；重复表格头被误删时把比例调高。

**Q：大表分块后表头还会重复入库，检索会不会召回多块重复内容？**
会召回多块，但每块自带表头+行号标注（「第 51-100 行，共 120 行」），Java 侧去重逻辑（`LinkedHashSet` 按 text 去重）能挡住完全相同的块。

**Q：能不能用 Chroma 原生的稀疏向量索引 + Search API 做 BM25 混合检索？**
**单机自建 Chroma 不行**（2026-09-17 实测，1.0.8 远端 + 1.5.9 本地双验证）：①创建带稀疏索引的集合被服务端拒绝「Sparse vector indexing is not enabled in local」；②稀疏向量 metadata 写入被拒（服务端 MetadataValue 枚举不含 sparse_vector）；③`/search` 端点 1.0.8 返回 404，1.5.9 报「not implemented for local executor」——官方文档明示 Search API 仅支持**分布式集群 / Chroma Cloud**。且即便上云，官方 `ChromaBm25EmbeddingFunction` 分词器是英文口径（空白切分 + 40 字 token 上限 + 英文停用词/词干化），600 字中文块会切出 0 个 token，中文语料必须自定义确定性稀疏 EF 并在查询端做跨语言复刻。**结论：单机部署的 BM25 用 Java 侧 Lucene 磁盘倒排（本方案）**，磁盘索引堆内存占用极小，中文分词（StandardAnalyzer 对 CJK 逐字 + ASCII 词）与确定性 chunk id 对齐，无跨语言哈希契约负担。

**Q：为什么 FAQ 单独拆两个文件，而不混进政策文档？**
问法和正文是两种检索模式：FAQ 的 `## Q：` 原子块让"用户原话"直接命中"问法"；政策正文靠语义相似兜底。拆开后还能按 `doc_no` 统计 FAQ 命中率，为后续优化（扩充高频问法）提供依据。

**Q：活动规则为什么用 JSON 而不是 markdown？**
活动规则是强结构化数据（编号/类型/时间窗口/规则列表），JSON 一条记录一个原子块、字段不丢、时间窗口可机读（`valid_from/valid_until`）。用 markdown 写活动会把日期埋进正文，Java 侧判断"活动是否进行中"就得靠大模型从文本里抠日期，既慢又不可靠。运维上活动规则通常也是运营系统导出的 JSON，天然同构。

**Q：已结束的活动留在库里，会不会误导用户？**
不会自动误导，但需要 Java 侧消费 `valid_until`：命中已过期活动块时，回答应带上"该活动已于 X 日结束"。这正是这批语料的设计目的——用三种时间态（已结束/进行中/未开始）检验系统的时效判断能力。彻底不想召回过期活动时，也可在检索层加元数据过滤（Chroma/Milvus 均支持按字段过滤）。

**Q：语料想给 Word 版政策文件用，格式约定还适用吗？**
适用。docx reader 按标题样式建层级、表格原子化；只需在文档首段放一行同样的元数据文本（或接受脚本从文件属性取 updated、temporal_tag 默认 CURRENT）。
