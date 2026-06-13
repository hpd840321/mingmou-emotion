#!/usr/bin/env python3
"""
Step 3 v2: 512-dim ArcFace 特征提取 → MySQL + Qdrant
使用 insightface w600k_r50 (ResNet-50 ArcFace, 512维)
替代原 128维 MobileFaceNet，提升聚类区分度

用法:
  python3 extract_features_512.py
  python3 extract_features_512.py --max 1000
  python3 extract_features_512.py --resume
"""
import os, sys, json, time, struct, base64, io, argparse, logging
import numpy as np
from PIL import Image
import psycopg2; import psycopg2.extras, requests

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

# Config
DB_HOST = "localhost"
DB_PORT = 5432
DB_USER = "emotion"
DB_PASS = "emotion"
DB_NAME = "emotion_platform"
QDRANT_URL = 'http://localhost:6333'
COLLECTION = 'face_features_512'  # 新 collection 避免与 128 维冲突
CHECKPOINT_FILE = '/tmp/feature_512_checkpoint.json'
BATCH_DB = 100
BATCH_QDRANT = 200
CONFIDENCE_THRESHOLD = 0.5
MIN_FACE_WIDTH = 50
CROP_MARGIN = 0.30

MODEL_PATH = os.path.expanduser('~/.insightface/models/buffalo_l/buffalo_l/w600k_r50.onnx')
FEATURE_DIM = 512


def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER, password=DB_PASS, database=DB_NAME)
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=DB_NAME,
                           charset='utf8mb4', cursorclass=psycopg2.extras.DictCursor)


def ensure_qdrant():
    r = requests.get(f'{QDRANT_URL}/collections/{COLLECTION}', timeout=10)
    if r.status_code == 200:
        dim = r.json()['result']['config']['params']['vectors']['size']
        if dim == FEATURE_DIM:
            log.info(f'Qdrant {COLLECTION} ready ({FEATURE_DIM}-dim)')
            return
        requests.delete(f'{QDRANT_URL}/collections/{COLLECTION}', timeout=10)
    payload = {'vectors': {'size': FEATURE_DIM, 'distance': 'Cosine'}}
    r = requests.put(f'{QDRANT_URL}/collections/{COLLECTION}', json=payload, timeout=10)
    log.info(f'Qdrant {COLLECTION} created ({FEATURE_DIM}-dim)')


def load_checkpoint():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f:
            return set(json.load(f).get('processed', []))
    return set()


def save_checkpoint(processed):
    with open(CHECKPOINT_FILE + '.tmp', 'w') as f:
        json.dump({'processed': sorted(processed)}, f)
    os.replace(CHECKPOINT_FILE + '.tmp', CHECKPOINT_FILE)


def get_qualified_faces(cursor, start_id=None, max_count=None, resume_set=None):
    query = """
        SELECT fr.id, fr.bbox, fr.confidence, ci.image_url
        FROM face_record fr
        JOIN class_image ci ON ci.id = fr.class_image_id
        WHERE fr.confidence >= %s
    """
    params = [CONFIDENCE_THRESHOLD]
    if start_id:
        query += ' AND fr.id >= %s'
        params.append(start_id)
    query += ' ORDER BY fr.id ASC'
    if max_count:
        query += ' LIMIT %s'
        params.append(max_count)
    cursor.execute(query, params)
    all_rows = cursor.fetchall()
    # Filter by face width + skip already processed
    result = []
    filtered = 0
    for r in all_rows:
        if resume_set and r['id'] in resume_set:
            continue
        w = parse_bbox_width(r['bbox'])
        if w < MIN_FACE_WIDTH:
            filtered += 1
            continue
        result.append(r)
    log.info(f'Qualified faces: {len(result)} (bbox filtered: {filtered}, skipped: {len(all_rows)-len(result)-filtered})')
    return result


def parse_bbox_width(bbox_str):
    try:
        b = json.loads(bbox_str)
        return int(b.get('width', 0))
    except:
        return 0


def crop_face(image_path, bbox_str):
    try:
        b = json.loads(bbox_str)
        x, y, w, h = int(b['x']), int(b['y']), int(b['width']), int(b['height'])
    except:
        return None
    mx = max(1, int(w * CROP_MARGIN))
    my = max(1, int(h * CROP_MARGIN))
    with Image.open(image_path) as img:
        left = max(0, x - mx)
        top = max(0, y - my)
        right = min(img.width, x + w + mx)
        bottom = min(img.height, y + h + my)
        if right <= left or bottom <= top:
            return None
        crop = img.crop((left, top, right, bottom))
        # Resize to 112x112 for ArcFace
        crop = crop.resize((112, 112), Image.LANCZOS)
        return np.array(crop)


def main():
    parser = argparse.ArgumentParser(description='512-dim ArcFace feature extraction')
    parser.add_argument('--max', type=int, default=0, help='Max faces to process')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--start-id', type=int, default=0)
    args = parser.parse_args()

    log.info('=== 512-dim ArcFace Feature Extraction ===')

    # Load model
    log.info(f'Loading ArcFace model: {MODEL_PATH}')
    model = insightface.model_zoo.ArcFaceONNX(MODEL_PATH)
    model.prepare(ctx_id=-1)
    log.info(f'Model ready. Input: {model.input_size}, Output: {FEATURE_DIM}-dim')

    ensure_qdrant()
    db = db_connect()
    cursor = db.cursor()
    processed = load_checkpoint() if args.resume else set()

    faces = get_qualified_faces(cursor, start_id=args.start_id or None,
                                max_count=args.max or None, resume_set=processed)
    if not faces:
        log.info('No faces to process')
        return

    t0 = time.time()
    total = len(faces)
    done = 0
    qdrant_batch = []

    for i, face in enumerate(faces):
        fr_id = face['id']
        img_path = face['image_url']

        if not os.path.exists(img_path):
            log.warning(f'  [{i+1}/{total}] FR#{fr_id}: image not found: {img_path[:80]}')
            processed.add(fr_id)
            continue

        # Crop face
        crop = crop_face(img_path, face['bbox'])
        if crop is None:
            processed.add(fr_id)
            continue

        # Extract 512-dim feature
        try:
            emb = model.get_feat(crop)
            if len(emb.shape) > 1:
                emb = emb[0]
            vec = emb.astype(np.float32).tolist()
        except Exception as e:
            log.warning(f'  [{i+1}/{total}] FR#{fr_id}: feature extraction failed: {e}')
            processed.add(fr_id)
            continue

        # Base64 encode feature blob (512 floats × 4 bytes = 2048 bytes)
        blob = struct.pack('f' * FEATURE_DIM, *vec)
        b64 = base64.b64encode(blob).decode('ascii')

        # Update MySQL
        cursor.execute(
            "UPDATE face_record SET face_encoding=%s WHERE id=%s",
            (b64, fr_id))
        done += 1

        # Queue Qdrant
        qdrant_batch.append({
            'id': fr_id,
            'vector': vec,
            'payload': {'face_record_id': fr_id}
        })

        # Flush
        if len(qdrant_batch) >= BATCH_QDRANT:
            requests.put(f'{QDRANT_URL}/collections/{COLLECTION}/points?wait=true',
                         json={'points': qdrant_batch}, timeout=30)
            qdrant_batch = []

        if done % BATCH_DB == 0:
            db.commit()
            processed.add(fr_id)
            save_checkpoint(processed)
            elapsed = time.time() - t0
            rate = (i + 1) / elapsed if elapsed > 0 else 0
            eta = (total - i - 1) / rate if rate > 0 else 0
            log.info(f'  Progress: {i+1}/{total} ({rate:.1f} faces/s, ETA {eta/60:.0f}min)')

        processed.add(fr_id)

    # Final flush
    db.commit()
    if qdrant_batch:
        requests.put(f'{QDRANT_URL}/collections/{COLLECTION}/points?wait=true',
                     json={'points': qdrant_batch}, timeout=30)
    save_checkpoint(processed)

    elapsed = time.time() - t0
    log.info(f'\n=== Done ===')
    log.info(f'Faces processed: {done}/{total} in {elapsed/60:.1f} min')
    log.info(f'Collection: {COLLECTION} ({FEATURE_DIM}-dim)')

    cursor.close()
    db.close()


if __name__ == '__main__':
    import insightface
    main()
