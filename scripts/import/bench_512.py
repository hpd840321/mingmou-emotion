#!/usr/bin/env python3
"""Benchmark 512-dim feature extraction speed."""
import os, json, time, pymysql, numpy as np
from PIL import Image
import insightface

model_path = os.path.expanduser('~/.insightface/models/buffalo_l/buffalo_l/w600k_r50.onnx')
model = insightface.model_zoo.ArcFaceONNX(model_path)
model.prepare(ctx_id=-1)

DB = pymysql.connect(host='nexus.craftsupport.cn', port=3307, user='root',
                     password='123456', database='emotion_platform',
                     charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
cur = DB.cursor()

N = 100
cur.execute("""
    SELECT fr.id, fr.bbox, ci.image_url FROM face_record fr
    JOIN class_image ci ON ci.id=fr.class_image_id
    WHERE fr.confidence>=0.5 AND fr.face_encoding IS NOT NULL
    LIMIT %s
""", (N,))
rows = cur.fetchall()

t0 = time.time()
success = 0
for row in rows:
    try:
        b = json.loads(row['bbox'])
        x, y, w, h = int(b['x']), int(b['y']), int(b['width']), int(b['height'])
        mx, my = max(1,int(w*0.3)), max(1,int(h*0.3))
        with Image.open(row['image_url']) as img:
            crop = img.crop((max(0,x-mx), max(0,y-my), min(img.width,x+w+mx), min(img.height,y+h+my)))
            crop = crop.resize((112,112), Image.LANCZOS)
            emb = model.get_feat(np.array(crop))
            if len(emb.shape) > 1: emb = emb[0]
        success += 1
    except:
        pass

elapsed = time.time() - t0
rate = N / elapsed
print(f'Processed: {success}/{N} in {elapsed:.1f}s ({rate:.1f} faces/s)')
print(f'Estimated for 68K faces: {68000/rate/60:.0f} min ({68000/rate/3600:.1f} hours)')
cur.close()
DB.close()
