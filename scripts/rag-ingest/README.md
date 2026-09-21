# rag-ingest —— 多格式文档「清洗-切分-入库」流水线

客服知识库 RAG 前置工具（纯 Python，不依赖 LlamaIndex）。完整教程见
[docs/guides/rag-corpus-ingestion-tutorial.md](../../docs/guides/rag-corpus-ingestion-tutorial.md)。
官方语料 23 个文件（20 md + 3 活动规则 JSON）灌库后 156 块。

## 快速开始

> **⚠️ 多 Python 共存机器必读**：本机若 `python` 与 `python3` 版本不一致（真实事故：`python`=系统 3.9、`python3`=venv 的 3.13），**一律用 `python3` 运行本脚本**。脚本入口已内置环境守卫：检测到解释器不在 .venv 中会直接拒绝并给出修复指引，而不是等最后一步报 ModuleNotFoundError。

```bash
cd scripts/rag-ingest
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
hash -r    # zsh 用 rehash：清掉 shell 命令缓存，确保 python/python3 都指向 venv

export SF_KEY=<你的 SiliconFlow key>          # 嵌入模型 Qwen/Qwen3-Embedding-8B 用（语料未变重跑 0 调用）
docker compose up -d chroma                   # 本机起库（ Chroma 已部署在远程服务器则跳过）

# 语料入库 —— 连远程服务器（先 curl http://<服务器IP>:8001/api/v2/heartbeat 验证连通）
python3 ingest.py --input ../../src/main/resources/corpus \
  --store chroma --uri http://120.48.5.195:8001
# 服务端开了 token 鉴权：export CHROMA_TOKEN=<token>；https 用 --uri https://<域名>

# 官方语料（20 md + 3 活动规则 JSON）入库本机 Chroma
python3 ingest.py --input ../../src/main/resources/corpus --store chroma

# 语料有删减时加 --rebuild（upsert 不删旧块，重建才清得掉孤儿块）
python3 ingest.py --input ../../src/main/resources/corpus --store chroma --rebuild

# 2) 生成多格式联调样例（docx/pdf/xlsx/png/html）并混合入库
python make_samples.py
python ingest.py --input ./samples --store chroma

# 3) 只解析切分看统计，不调 API 不落库
python ingest.py --input ../../src/main/resources/corpus --dry-run
```

## 常用参数

| 参数 | 默认 | 说明 |
|------|------|------|
| `--store` | `chroma` | `chroma` / `milvus` |
| `--uri` | 按库 8000/19530 | 向量库地址 |
| `--collection` | `kb_customer_service` | collection 名 |
| `--chunker` | `structure` | `structure` 按标题层级 ｜ `semantic` 句子嵌入断点（txt/OCR 文本） |
| `--parent-child` | 关 | 超长块拆子块检索、父节全文挂 `parent_text` |
| `--max-chars` / `--overlap` | 600 / 80 | 硬约束兜底（≈512 token） |
| `--batch-size` | 16 | 嵌入批大小，免费档限流时调小 |
| `--dry-run` / `--skip-verify` | — | 只解析不落库 / 跳过召回自检 |

## 文件说明

- `ingest.py` —— 主流水线：readers（10 类分型解析）→ cleaners（规范化/页眉页脚/乱码/去重/脱敏）→ chunkers（structure/semantic/表格原子块/JSON 活动原子块/parent-child）→ writer（Chroma/Milvus 幂等 upsert + 召回自检）
- `make_samples.py` —— 生成联调样例：docx（标题+表格）、pdf（重复页眉页脚+扫描页）、xlsx（大表分块）、png（图片 OCR）、html、json（活动规则+时间约束）
- `requirements.txt` —— 依赖（PaddleOCR 为可选：装了才启用扫描件/图片 OCR，缺了自动降级占位）

## 与 Java 侧的硬约定

嵌入模型必须与后端 `EmbeddingService` 同源（`Qwen/Qwen3-Embedding-8B`，4096 维）；换模型 = 换向量空间，全库必须重灌。metadata 契约（`source/doc_type/page/sheet/section/doc_no/domain/temporal_tag/kind/parent_text/valid_from/valid_until`）、`temporal_tag=HISTORICAL` 的时效隔离语义、活动规则 JSON 的 `valid_from/valid_until` 时间窗口判定，见教程第 7 节与第 2.2 节。
