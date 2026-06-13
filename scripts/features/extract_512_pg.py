#!/usr/bin/env python3
"""512-dim ArcFace extraction → PostgreSQL + Qdrant"""
import os, sys, json, time, struct, base64, argparse, logging
import numpy as np, psycopg2, psycopg2.extras, requests
from PIL import Image

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

DB = "host=localhost port=5432 dbname=emotion_platform user=emotion password=emotion"
QDRANT_URL = 'http://localhost:6333'
COLLECTION = 'face_features_512'
CHECKPOINT = '/tmp/extract_512_pg_checkpoint.json'
CONF = 0.5; MIN_W = 50; MARGIN = 0.30
MODEL_PATH = os.path.expanduser('~/.insightface/models/buffalo_l/buffalo_l/w600k_r50.onnx')

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--max', type=int, default=0); parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()

    import insightface
    model = insightface.model_zoo.ArcFaceONNX(MODEL_PATH); model.prepare(ctx_id=-1)
    log.info(f'Model ready: {model.input_size} → 512-dim')

    # Ensure Qdrant
    r = requests.get(f'{QDRANT_URL}/collections/{COLLECTION}', timeout=10)
    if r.status_code != 200:
        requests.put(f'{QDRANT_URL}/collections/{COLLECTION}', json={'vectors':{'size':512,'distance':'Cosine'}}, timeout=10)

    conn = psycopg2.connect(DB)
    cur = conn.cursor(cursor_factory=psycopg2.extras.DictCursor)

    done = set()
    if args.resume and os.path.exists(CHECKPOINT):
        with open(CHECKPOINT) as f: done = set(json.load(f).get('done',[]))

    cur.execute("SELECT fr.id, fr.bbox, fr.confidence, ci.image_url FROM face_record fr JOIN class_image ci ON ci.id=fr.class_image_id WHERE fr.confidence>=%s ORDER BY fr.id", (CONF,))
    rows = cur.fetchall()
    # Filter
    faces = []
    for r in rows:
        if r['id'] in done: continue
        try: w = int(json.loads(r['bbox'])['width'])
        except: w = 0
        if w < MIN_W: continue
        faces.append(r)
    if args.max: faces = faces[:args.max]
    log.info(f'Qualified: {len(faces)} faces (conf>={CONF}, w>={MIN_W})')

    t0 = time.time(); done_cnt = 0; qdrant_batch = []
    for i, row in enumerate(faces):
        fr_id = row['id']; path = row['image_url']
        if not os.path.exists(path): done.add(fr_id); continue

        b = json.loads(row['bbox'])
        mx = max(1, int(b['width']*MARGIN)); my = max(1, int(b['height']*MARGIN))
        try:
            with Image.open(path) as img:
                crop = img.crop((max(0,b['x']-mx), max(0,b['y']-my), min(img.width,b['x']+b['width']+mx), min(img.height,b['y']+b['height']+my)))
                crop = crop.resize((112,112), Image.LANCZOS)
                emb = model.get_feat(np.array(crop))
                if len(emb.shape)>1: emb = emb[0]
                vec = emb.astype(np.float32).tolist()
        except Exception as e:
            log.debug(f'  [{i+1}] FR#{fr_id}: {e}'); done.add(fr_id); continue

        blob = struct.pack('f'*512, *vec); b64 = base64.b64encode(blob).decode()
        cur.execute("UPDATE face_record SET face_encoding=%s WHERE id=%s", (b64, fr_id))
        qdrant_batch.append({'id':fr_id, 'vector':vec, 'payload':{'face_record_id':fr_id}})
        done_cnt += 1; done.add(fr_id)

        if len(qdrant_batch) >= 200:
            requests.put(f'{QDRANT_URL}/collections/{COLLECTION}/points?wait=true', json={'points':qdrant_batch}, timeout=30)
            qdrant_batch = []
        if done_cnt % 100 == 0:
            conn.commit()
            with open(CHECKPOINT+'.tmp','w') as f: json.dump({'done':sorted(done)}, f)
            os.replace(CHECKPOINT+'.tmp', CHECKPOINT)
            elapsed = time.time()-t0; rate = (i+1)/elapsed if elapsed>0 else 0
            log.info(f'  {i+1}/{len(faces)} ({rate:.1f}/s, ETA {(len(faces)-i-1)/rate/60:.0f}min)')

    conn.commit()
    if qdrant_batch: requests.put(f'{QDRANT_URL}/collections/{COLLECTION}/points?wait=true', json={'points':qdrant_batch}, timeout=30)
    elapsed = time.time()-t0
    log.info(f'\nDone: {done_cnt} faces in {elapsed/60:.1f}min')
    cur.close(); conn.close()

if __name__ == '__main__': main()
