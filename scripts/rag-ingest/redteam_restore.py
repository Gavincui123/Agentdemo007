#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""红队演示工具：把 corpus-redteam 毒片（重）灌回 Chroma 生产集合（零嵌入 API 调用）。

用途：演示「污染语料入库→召回→LLM」需要毒片常驻 kb_customer_service；演示后可按
docs/rag-redteam-conflict-demo.md 的 delete 命令清理，本脚本随时零成本恢复。前提：
毒片曾正常入库过一次（embedding-cache.json 已有其向量）。id/metadata 与 ingest.py
完全同源（确定性），恢复即比特级还原，不会产生重复块。

原理：ingest.py 同源管线（READERS→clean→structure_chunks→finalize_ids）重建切块，
id/metadata 与原入库完全一致；向量从 embedding-cache.json 取（键=sha256(model\\0text[:4000])，
红队入库时已写入缓存），不再调 SiliconFlow。任何一块缓存 miss 即中止（防半灌）。
"""

import hashlib
import json
import sys
import urllib.request
from pathlib import Path

PROJ = Path("/Users/cuizhifeng/Documents/shoushunjob/Agentdemo007")
sys.path.insert(0, str(PROJ / "scripts" / "rag-ingest"))
import ingest  # noqa: E402  (__main__ 守卫已确认，import 无副作用)

INPUT = PROJ / "src" / "main" / "resources" / "corpus-redteam"
BASE = ("http://120.48.5.195:8001/api/v2/tenants/default_tenant"
        "/databases/default_database/collections/88a090ee-9264-40a4-92c7-53aef0a28e53")
MODEL = "Qwen/Qwen3-Embedding-8B"
CACHE = json.load(open(PROJ / "scripts" / "rag-ingest" / "embedding-cache.json"))

files = ingest.collect_files(INPUT)
chunks = []
for f in files:
    blocks, meta = ingest.READERS[f.suffix.lower()](f)
    source = str(f.relative_to(INPUT))
    for b in blocks:
        b.source = source
    blocks = ingest.clean_document(blocks)
    doc_type = ingest.DOC_TYPE_LABEL.get(f.suffix.lstrip("."), f.suffix.lstrip("."))
    cs = ingest.structure_chunks(blocks, meta, source, doc_type, 600, 80)
    ingest.finalize_ids(cs)
    chunks.extend(cs)
    print(f"[parse] {source}: {len(cs)} 块")

ids, vecs, docs, metas = [], [], [], []
for c in chunks:
    key = hashlib.sha256(f"{MODEL}\x00{c.text[:4000]}".encode("utf-8")).hexdigest()
    if key not in CACHE:
        print(f"[abort] 缓存 miss：{c.id} {c.meta['source']} 「{c.text[:30]}…」——请改走 ingest.py+SF_KEY")
        sys.exit(1)
    ids.append(c.id)
    vecs.append(CACHE[key])
    docs.append(c.text)
    metas.append(c.meta)
print(f"[cache] {len(ids)}/{len(chunks)} 块向量命中缓存，零 API 调用")

body = json.dumps({"ids": ids, "embeddings": vecs, "documents": docs, "metadatas": metas}).encode()
req = urllib.request.Request(BASE + "/upsert", data=body,
                             headers={"Content-Type": "application/json"})
print("[upsert]", urllib.request.urlopen(req, timeout=30).read().decode()[:200])

check = urllib.request.Request(BASE + "/get",
                               data=json.dumps({"where": {"doc_no": {"$in": [
                                   "KB-POISON-01", "KB-POISON-02", "KB-POISON-03"]}},
                                   "include": ["metadatas"], "limit": 100}).encode(),
                               headers={"Content-Type": "application/json"})
restored = len(json.load(urllib.request.urlopen(check, timeout=30)).get("ids", []))
print(f"[verify] 复核命中毒块数: {restored}（期望 {len(chunks)}）")
sys.exit(0 if restored == len(chunks) else 2)
