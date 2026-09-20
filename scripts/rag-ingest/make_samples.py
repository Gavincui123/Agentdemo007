#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成多格式联调样例文档（喂给 ingest.py 做「分型解析-清洗-切分-入库」端到端演练）。

产物（默认输出到 ./samples/）：
  sample-policy.docx   Word：标题层级 + 正文 + 表格（演示 docx reader + 表格原子块）
  sample-notice.pdf    PDF：第 1 页文本页（带重复页眉页脚，演示页眉页脚去重 + 简易表格线）
                       第 2 页纯图像扫描页（演示扫描页判定与 OCR 降级）
  sample-price.xlsx    Excel：小表（运费标准）+ 120 行大表（促销价目，演示分块带头）
  sample-signboard.png 纯图片：含文字的「退款申请单」照片（演示图片 OCR / 占位降级）
  sample-faq.html      HTML：FAQ 页面（演示 html reader）
  sample-activities.json JSON：活动规则条目（演示 json reader + valid_from/valid_until 时间约束）

每个生成器独立 try/except：缺哪类依赖就跳过哪类，不互相拖垮。
用法：python make_samples.py [--out ./samples]
"""

from __future__ import annotations

import argparse
from pathlib import Path

# 各平台常见中文字体（PIL 画图与扫描页生成用；找不到则退化为英文内容）
CJK_FONT_CANDIDATES = [
    "/System/Library/Fonts/PingFang.ttc",            # macOS
    "/System/Library/Fonts/Hiragino Sans GB.ttc",    # macOS
    "/System/Library/Fonts/STHeiti Light.ttc",       # macOS
    "C:/Windows/Fonts/msyh.ttc",                     # Windows
    "C:/Windows/Fonts/simhei.ttf",                   # Windows
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",# Linux
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",  # Linux
]


def find_cjk_font() -> str | None:
    for p in CJK_FONT_CANDIDATES:
        if Path(p).exists():
            return p
    return None


def make_docx(out: Path) -> None:
    try:
        from docx import Document
    except ImportError:
        print("[skip] sample-policy.docx（缺 python-docx）")
        return
    doc = Document()
    doc.add_heading("换货补充政策", 0)
    doc.add_paragraph("文档编号：KB-EXCHANGE ｜ 知识域：received_return_policy ｜ "
                      "更新日期：2026-09-15 ｜ 时效标签：CURRENT")
    doc.add_heading("换货范围", level=1)
    doc.add_paragraph("仅存在质量问题的商品支持换货，更换为同款同色。贴身衣物、拆封食品、"
                      "定制类商品不支持换货。换货需在签收后 7 天内且在 30 天质量问题窗口内申请。")
    doc.add_heading("换货时效表", level=1)
    doc.add_paragraph("不同物流方式下的换货参考时效如下表：")
    table = doc.add_table(rows=4, cols=3)
    table.style = "Table Grid"
    data = [
        ("物流方式", "寄回时效", "换出时效"),
        ("顺丰快递", "1-2 日", "3-5 日"),
        ("普通快递", "3-5 日", "5-7 日"),
        ("自送网点", "当日", "5-7 日"),
    ]
    for r, row in enumerate(data):
        for c, value in enumerate(row):
            table.rows[r].cells[c].text = value
    doc.add_heading("换货运费", level=1)
    doc.add_paragraph("质量问题换货的往返运费均由商家承担，请在申请通过后使用商家提供的到付地址寄回。")
    doc.save(str(out / "sample-policy.docx"))
    print(f"[ok] {out / 'sample-policy.docx'}")


def make_pdf(out: Path) -> None:
    try:
        import fitz  # PyMuPDF
    except ImportError:
        print("[skip] sample-notice.pdf（缺 PyMuPDF）")
        return
    doc = fitz.open()

    # 第 1 页：文本页。页眉页脚在每一页重复出现（演示 strip_pdf_headers_footers 去重）
    page = doc.new_page(width=595, height=842)  # A4
    page.insert_text(fitz.Point(60, 50), "电商内部文件（页眉：重复行演示）",
                     fontname="china-s", fontsize=9)
    page.insert_text(fitz.Point(60, 90), "大促活动公告", fontname="china-s", fontsize=18)
    body_lines = [
        "一、活动时间：2026 年 9 月 20 日 0 点至 9 月 30 日 24 点。",
        "二、满减规则：全场满 199 减 20，满 399 减 50，可叠加会员折扣。",
        "三、秒杀场次：每日 10 点、20 点两场，秒杀商品每人限购 2 件。",
        "四、活动商品不参与七天无理由退货，存在质量问题的除外。",
        "五、优惠券发放：活动期间每日签到可领 5 元无门槛券，有效期 7 天。",
        "六、售后服务：活动订单的退款窗口仍为支付后 30 天，退款按原渠道 3-7 个工作日到账。",
    ]
    y = 130
    for line in body_lines:
        page.insert_text(fitz.Point(60, y), line, fontname="china-s", fontsize=12)
        y += 24
    # 简易带线表格（演示 pdfplumber 表格抽取）
    y += 20
    page.insert_text(fitz.Point(60, y), "配送时效表：", fontname="china-s", fontsize=12)
    y += 16
    table_rows = [["线路", "时效", "运费"], ["华东", "3 日", "8 元"], ["华南", "4 日", "8 元"]]
    col_x = [60, 220, 340, 460]
    for r, row in enumerate(table_rows):
        ry = y + r * 22
        for c, value in enumerate(row):
            page.insert_text(fitz.Point(col_x[c] + 4, ry + 15), value,
                             fontname="china-s", fontsize=10)
        for x in col_x:
            page.draw_line(fitz.Point(x, ry), fitz.Point(x, ry + 22))
        page.draw_line(fitz.Point(col_x[0], ry), fitz.Point(col_x[-1], ry))
    ry = y + len(table_rows) * 22
    page.draw_line(fitz.Point(col_x[0], ry), fitz.Point(col_x[-1], ry))
    page.insert_text(fitz.Point(60, 800), "第 1 页 / 共 2 页（页脚：重复行演示）",
                     fontname="china-s", fontsize=9)

    # 第 2 页：扫描图像页（整页只有一张图，无文本 → 触发扫描页判定）
    scan_page = doc.new_page(width=595, height=842)
    scan_page.insert_text(fitz.Point(60, 50), "电商内部文件（页眉：重复行演示）",
                          fontname="china-s", fontsize=9)
    scan_png = _render_scan_image()
    if scan_png:
        scan_page.insert_image(fitz.Rect(40, 80, 555, 620), stream=scan_png)
    doc.save(str(out / "sample-notice.pdf"))
    doc.close()
    print(f"[ok] {out / 'sample-notice.pdf'}")


def _render_scan_image() -> bytes | None:
    """用 PIL 把文字渲染成图片（模拟扫描件）；无中文字体时返回 None（扫描页留白）。"""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print("[skip] 扫描页图像（缺 Pillow）")
        return None
    font_path = find_cjk_font()
    image = Image.new("RGB", (1030, 920), "white")
    draw = ImageDraw.Draw(image)
    lines = [
        "售后工单（扫描件演示）",
        "工单号：TS-2026-0915-001",
        "客户：张三（演示账户 10086，金卡会员）",
        "订单：ORD-001，签收日期 2026-09-10",
        "诉求：商品屏幕左侧有亮点，申请质量问题换货。",
        "处理意见：符合质量问题换货条件，30 天窗口内，运费商家承担。",
        "审批：同意换货，换出时效按普通快递 5-7 日执行。",
    ]
    if font_path:
        font = ImageFont.truetype(font_path, 30)
        for i, line in enumerate(lines):
            draw.text((40, 40 + i * 60), line, fill="black", font=font)
    else:
        draw.text((40, 40), "After-sales ticket TS-2026-0915-001 (demo scan)", fill="black")
    import io

    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()


def make_xlsx(out: Path) -> None:
    try:
        from openpyxl import Workbook
    except ImportError:
        print("[skip] sample-price.xlsx（缺 openpyxl）")
        return
    wb = Workbook()
    ws = wb.active
    ws.title = "运费标准"
    ws.append(["地区", "首重运费", "续重每公斤", "偏远加收"])
    for row in [
        ("华东", "8 元", "2 元", "0 元"),
        ("华南", "8 元", "2 元", "0 元"),
        ("华北", "8 元", "2 元", "0 元"),
        ("东北", "10 元", "3 元", "0 元"),
        ("西南", "10 元", "3 元", "0 元"),
        ("新疆/西藏", "15 元", "5 元", "10 元"),
    ]:
        ws.append(list(row))
    ws2 = wb.create_sheet("促销价目")
    ws2.append(["商品编码", "商品名称", "活动价", "限购"])
    for i in range(1, 121):  # 120 行大表 → 演示按 50 行分块、每块重复表头
        ws2.append([f"SKU-{i:04d}", f"演示商品 {i}", 9.9 + (i % 20), 2 if i % 3 == 0 else 0])
    wb.save(str(out / "sample-price.xlsx"))
    print(f"[ok] {out / 'sample-price.xlsx'}")


def make_png(out: Path) -> None:
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print("[skip] sample-signboard.png（缺 Pillow）")
        return
    image = Image.new("RGB", (900, 420), "white")
    draw = ImageDraw.Draw(image)
    font_path = find_cjk_font()
    lines = ["退款申请单（图片 OCR 演示）", "订单号：ORD-002", "退款原因：七天无理由退货",
             "退款金额：128.00 元", "退款方式：原支付渠道 3-7 个工作日到账"]
    if font_path:
        font = ImageFont.truetype(font_path, 34)
        for i, line in enumerate(lines):
            draw.text((40, 40 + i * 70), line, fill="black", font=font)
    else:
        for i, line in enumerate(["REFUND REQUEST", "Order: ORD-002", "Reason: 7-day no-reason return",
                                  "Amount: CNY 128.00", "Refund: 3-7 working days via original channel"]):
            draw.text((40, 40 + i * 70), line, fill="black")
    image.save(str(out / "sample-signboard.png"))
    print(f"[ok] {out / 'sample-signboard.png'}")


def make_html(out: Path) -> None:
    html = """<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><title>配送 FAQ（HTML 样例）</title></head>
<body>
  <nav>导航栏（清洗时应被剔除）</nav>
  <h1>配送常见问题</h1>
  <h2>偏远地区配送要多久？</h2>
  <p>偏远地区（新疆、西藏、内蒙古部分地区）发货后 5-7 日送达，比标准时效多 2 日。</p>
  <h2>快递显示已签收但没收到货怎么办？</h2>
  <p>请联系客服登记异常件，物流专员 24 小时内核实；确认丢失的订单将安排补发或全额退款。</p>
  <h2>各线路运费表</h2>
  <table>
    <tr><th>线路</th><th>时效</th><th>运费</th></tr>
    <tr><td>华东</td><td>3 日</td><td>8 元</td></tr>
    <tr><td>华南</td><td>4 日</td><td>8 元</td></tr>
  </table>
  <aside>广告位（清洗时应被剔除）</aside>
</body>
</html>
"""
    (out / "sample-faq.html").write_text(html, encoding="utf-8")
    print(f"[ok] {out / 'sample-faq.html'}")


def make_json(out: Path) -> None:
    """活动规则 JSON 样例（演示 json reader：条目原子块 + valid_from/valid_until 时间约束）。"""
    import json

    data = {
        "doc_no": "KB-ACT-SAMPLE",
        "domain": "promotion_and_member_policy",
        "updated": "2026-09-15",
        "temporal_tag": "CURRENT",
        "activities": [
            {
                "id": "ACT-S-001",
                "name": "周末闪购",
                "type": "限时折扣",
                "time": {"start": "2026-09-19", "end": "2026-09-21"},
                "rules": ["百货类 8 折起", "单用户限购 5 件"],
                "superposition": "可与金卡 95 折叠加",
                "restrictions": ["活动商品不参与七天无理由退货（质量问题除外）"],
            },
            {
                "id": "ACT-S-002",
                "name": "国庆家电焕新",
                "type": "以旧换新",
                "time": {"start": "2026-10-01", "end": "2026-10-08"},
                "rules": ["大家电回收补贴最高 800 元", "送装一体免费上楼"],
                "restrictions": ["补贴以旧机评估为准", "新机激活后仅支持质量问题退货"],
            },
        ],
    }
    (out / "sample-activities.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[ok] {out / 'sample-activities.json'}")


def main() -> None:
    parser = argparse.ArgumentParser(description="生成多格式联调样例文档")
    parser.add_argument("--out", default="./samples", help="样例输出目录")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    make_docx(out)
    make_pdf(out)
    make_xlsx(out)
    make_png(out)
    make_html(out)
    make_json(out)
    print(f"\n[done] 样例已生成到 {out.resolve()}")
    print("下一步：python ingest.py --input " + str(out.resolve()) + " --store chroma")


if __name__ == "__main__":
    main()
