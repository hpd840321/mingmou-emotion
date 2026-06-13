#!/usr/bin/env python3
"""从 MySQL face_encoding 灌入 Qdrant（跳过 gRPC，直接读已有向量）"""
import sys, json, struct, base64, time
import pymysql, requests, logging

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

QDRANT_URL = 'http://localhost:6333'
COLLECTION = 'face_features'
DB_HOST = 'nexus.craftsupport.cn'
DB_PORT = 3307
DB_USER = 'root'
DB_PASS = '123456'
DB_NAME = 'emotion_platform'
BATCH = 500

def main():
    # Ensure Qdrant collection
    r = requests.get(f'{QDRANT_URL}/collections/{COLLECTION}', timeout=10)
    if r.status_code != 200:
        requests.put(f'{QDRANT_URL}/collections/{COLLECTION}',
                     json={'vectors': {'size': 128, 'distance': 'Cosine'}}, timeout=10)
        log.info('Qdrant collection created')
    else:
        log.info('Qdrant collection exists')

    db = pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                         password=DB_PASS, database=DB_NAME,
                         charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)
    cursor = db.cursor()
    cursor.execute("SELECT COUNT(*) n FROM face_record WHERE face_encoding IS NOT NULL AND face_encoding != ''")
    total = cursor.fetchone()['n']
    log.info(f'Total face_encodings in MySQL: {total}')

    cursor.execute("SELECT id, face_encoding FROM face_record WHERE face_encoding IS NOT NULL AND face_encoding != '' ORDER BY id")
    points = []
    uploaded = 0
    t0 = time.time()
    for row in cursor:
        try:
            raw = base64.b64decode(row['face_encoding'])
            vec = list(struct.unpack('f' * 128, raw))
        except:
            continue
        points.append({'id': row['id'], 'vector': vec,
                       'payload': {'face_record_id': row['id']}})
        if len(points) >= BATCH:
            r = requests.put(f'{QDRANT_URL}/collections/{COLLECTION}/points?wait=true',
                             json={'points': points}, timeout=60)
            uploaded += len(points)
            points = []
            if uploaded % 10000 == 0:
                elapsed = time.time() - t0
                log.info(f'  {uploaded}/{total} ({uploaded/elapsed:.0f} pts/s)')
    if points:
        requests.put(f'{QDRANT_URL}/collections/{COLLECTION}/points?wait=true',
                     json={'points': points}, timeout=60)
        uploaded += len(points)

    elapsed = time.time() - t0
    log.info(f'Done: {uploaded} points in {elapsed:.0f}s ({uploaded/elapsed:.0f} pts/s)')
    cursor.close()
    db.close()

if __name__ == '__main__':
    main()
