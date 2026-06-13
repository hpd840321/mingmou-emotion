#!/usr/bin/env python3
"""Test face cropping quality at different sizes for ArcFace 512-dim extract."""
import json, struct, base64, pymysql
from PIL import Image
import numpy as np

DB = pymysql.connect(host='nexus.craftsupport.cn', port=3307, user='root',
                     password='123456', database='emotion_platform',
                     charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
cur = DB.cursor()

# Pick samples at different face widths: 20px, 40px, 60px, 100px
samples = {}
for w_range in [(20, 25), (40, 45), (60, 65), (100, 120)]:
    cur.execute("""
        SELECT fr.id, fr.bbox, fr.confidence, ci.image_url
        FROM face_record fr JOIN class_image ci ON ci.id=fr.class_image_id
        WHERE fr.confidence>=0.5 AND fr.face_encoding IS NOT NULL
        AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(SUBSTRING_INDEX(bbox,'width\":',-1),',',1),'.',1) AS UNSIGNED)
            BETWEEN %s AND %s
        LIMIT 1
    """, w_range)
    row = cur.fetchone()
    if row:
        samples[w_range[0]] = row

for px, row in sorted(samples.items()):
    b = json.loads(row['bbox'])
    x, y, w, h = int(b['x']), int(b['y']), int(b['width']), int(b['height'])
    mx = max(1, int(w * 0.3))
    my = max(1, int(h * 0.3))

    with Image.open(row['image_url']) as img:
        left = max(0, x - mx)
        top = max(0, y - my)
        right = min(img.width, x + w + mx)
        bottom = min(img.height, y + h + my)
        crop = img.crop((left, top, right, bottom))
        crop.save(f'/tmp/face_{px}px_original.jpg')

        resized = crop.resize((112, 112), Image.LANCZOS)
        resized.save(f'/tmp/face_{px}px_112x112.jpg')

        print(f'  {px}px face (conf={row["confidence"]:.2f}): '
              f'original={crop.size} → 112×112 '
              f'(upscale {(112/max(crop.size)):.1f}×)')

print('\nSaved to /tmp/face_*px_*.jpg')
cur.close()
DB.close()
