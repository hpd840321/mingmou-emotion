#!/usr/bin/env python3
"""图片导入脚本：扫描 data/ 目录，写入 PostgreSQL class_image 表"""
import os, sys, re, psycopg2
from pathlib import Path
from datetime import datetime, timezone, timedelta

DB = "host=localhost port=5432 dbname=emotion_platform user=emotion password=emotion"
DATA = Path("/media/zebra/data/官渡一中初一班-0526/data")
CST = timezone(timedelta(hours=8))

PERIOD_MAP = {
    "早读-到校": "arrival", "第1节": "period_1", "第2节": "period_2",
    "第3节": "period_3", "第4节": "period_4", "第5节": "period_5",
    "第6节": "period_6", "第7节": "period_7", "第8节": "period_8",
    "课间操": "recess", "午餐-午休": "lunch", "课外活动-放学": "afterclass",
}
DATE_RE = re.compile(r"(\d{4})-(\d{2})-?(\d{2})")
TIME_RE = re.compile(r".*(\d{4})(\d{2})(\d{2})_?(\d{2})(\d{2})(\d{2}).*\.jpg$")

conn = psycopg2.connect(DB)
cur = conn.cursor()

total = imported = 0
for school_dir in sorted(DATA.iterdir()):
    if not school_dir.is_dir(): continue
    for class_dir in sorted(school_dir.iterdir()):
        if not class_dir.is_dir(): continue
        for date_dir in sorted(class_dir.iterdir()):
            if not date_dir.is_dir(): continue
            m = DATE_RE.match(date_dir.name)
            if not m: continue
            y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
            for period_dir in sorted(date_dir.iterdir()):
                if not period_dir.is_dir(): continue
                pk = PERIOD_MAP.get(period_dir.name, "other")
                for img in sorted(period_dir.glob("*.jpg")):
                    total += 1
                    url = str(img.resolve())
                    # Check duplicate
                    cur.execute("SELECT 1 FROM class_image WHERE image_url=%s", (url,))
                    if cur.fetchone(): continue
                    # Parse capture time
                    tm = TIME_RE.match(img.name)
                    if tm:
                        ct = datetime(y, mo, d, int(tm.group(4)), int(tm.group(5)), int(tm.group(6)), tzinfo=CST)
                    else:
                        ct = datetime(y, mo, d, 12, 0, 0, tzinfo=CST)
                    cur.execute(
                        "INSERT INTO class_image (class_id,image_url,capture_time,period_label,source,status) VALUES (1,%s,%s,%s,'auto_scan','PENDING')",
                        (url, ct, pk))
                    imported += 1
        conn.commit()
        print(f"  {date_dir.name}: +{imported} images (total scanned: {total})")

conn.commit()
print(f"\nDone: {imported} imported / {total} scanned")
cur.execute("SELECT COUNT(*) FROM class_image")
print(f"class_image rows: {cur.fetchone()[0]}")
cur.close(); conn.close()
