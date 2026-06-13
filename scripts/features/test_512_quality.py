#!/usr/bin/env python3
"""Test ArcFace 512-dim quality on real face crops."""
import os, json, pymysql, numpy as np
from PIL import Image
import insightface

model_path = os.path.expanduser('~/.insightface/models/buffalo_l/buffalo_l/w600k_r50.onnx')
model = insightface.model_zoo.ArcFaceONNX(model_path)
model.prepare(ctx_id=-1)

DB = pymysql.connect(host='nexus.craftsupport.cn', port=3307, user='root',
                     password='123456', database='emotion_platform',
                     charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
cur = DB.cursor()

# Pick 3 faces: one 25px, one 50px, one 100px
features = {}
for px_label, w_range in [('25px', (23,28)), ('50px', (48,53)), ('100px', (95,110))]:
    cur.execute("""
        SELECT fr.id, fr.bbox, fr.confidence, ci.image_url
        FROM face_record fr JOIN class_image ci ON ci.id=fr.class_image_id
        WHERE fr.confidence>=0.5 AND fr.face_encoding IS NOT NULL
        AND CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(SUBSTRING_INDEX(bbox,'width\":',-1),',',1),'.',1) AS UNSIGNED)
            BETWEEN %s AND %s
        LIMIT 1
    """, w_range)
    row = cur.fetchone()
    if not row: continue

    b = json.loads(row['bbox'])
    x, y, w, h = int(b['x']), int(b['y']), int(b['width']), int(b['height'])
    mx, my = max(1,int(w*0.3)), max(1,int(h*0.3))
    with Image.open(row['image_url']) as img:
        crop = img.crop((max(0,x-mx), max(0,y-my), min(img.width,x+w+mx), min(img.height,y+h+my)))
        crop = crop.resize((112,112), Image.LANCZOS)
        emb = model.get_feat(np.array(crop))
        if len(emb.shape) > 1: emb = emb[0]
        features[px_label] = emb
        print(f'  {px_label} face (conf={row["confidence"]:.2f}, '
              f'orig={w}x{h}): feature norm={np.linalg.norm(emb):.4f}, '
              f'first 3 dims={emb[:3].tolist()}')

# Cross similarity
print('\nCosine similarities:')
for a in features:
    for b in features:
        if a < b:
            sim = np.dot(features[a], features[b]) / (np.linalg.norm(features[a]) * np.linalg.norm(features[b]))
            print(f'  {a} × {b}: cos={sim:.4f}')
cur.close()
DB.close()
