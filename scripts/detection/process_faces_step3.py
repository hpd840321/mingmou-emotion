#!/usr/bin/env python3
"""
Step 3: Face Feature Extraction + Registration to Qdrant
策略：对每张原图调用一次 gRPC Analyze（含 RECOGNITION），按 bbox 匹配已有
face_record，提取 128-dim 特征写入 MySQL + Qdrant。
避免对裁剪图逐张调用（face_server 无法处理过小图像）。

Usage:
  python3 process_faces_step3.py                          # 全量处理
  python3 process_faces_step3.py --max 100                # 仅处理前 100 张图
  python3 process_faces_step3.py --resume                  # 断点续传
  python3 process_faces_step3.py --start-id 100            # 从指定 class_image ID 开始
"""

import os, sys, json, time, struct, base64
from datetime import datetime
import argparse
import logging

import grpc
import requests
import pymysql

sys.path.insert(0, '/tmp/proto_out')
from inference_pb2_grpc import FaceServiceStub
from inference_pb2 import FaceAnalysisRequest

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

FACE_SERVER = 'localhost:50053'
QDRANT_URL = 'http://localhost:6333'
QDRANT_COLLECTION = 'face_features'
DB_HOST = '192.168.3.12'
DB_PORT = 3307
DB_USER = 'root'
DB_PASS = '123456'
DB_NAME = 'emotion_platform'
CHECKPOINT_FILE = '/tmp/face_feature_checkpoint.json'

BATCH_SIZE = 200
MAX_RETRIES = 3
GRPC_TIMEOUT = 180
BBOX_MATCH_THRESHOLD = 30  # center distance in pixels
DOCKER_CONTAINER = 'docker-face-1-1'

FEAT_DETECT = 0x01
FEAT_RECOGNITION = 0x02
FEAT_QUALITY = 0x20
FEATURES = FEAT_DETECT | FEAT_RECOGNITION | FEAT_QUALITY


def db_connect():
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=DB_NAME,
                           charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)


def ensure_qdrant():
    resp = requests.get('%s/collections/%s' % (QDRANT_URL, QDRANT_COLLECTION), timeout=10)
    if resp.status_code == 200:
        d = resp.json()['result']['config']['params']['vectors']
        if isinstance(d, dict) and d.get('size') == 128:
            log.info('Qdrant %s ready (128-dim)', QDRANT_COLLECTION)
            return
        requests.delete('%s/collections/%s' % (QDRANT_URL, QDRANT_COLLECTION), timeout=10)
    payload = {'vectors': {'size': 128, 'distance': 'Cosine'},
               'hnsw_config': {'m': 16, 'ef_construct': 100}}
    r = requests.put('%s/collections/%s' % (QDRANT_URL, QDRANT_COLLECTION), json=payload, timeout=10)
    if r.status_code not in (200, 201):
        log.error('Qdrant create failed: %s', r.text[:200])
        sys.exit(1)
    log.info('Qdrant collection created (128-dim)')


def load_cp():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f:
            d = json.load(f)
        return set(d.get('processed_ci_ids', [])), set(d.get('failed_ci_ids', [])), d
    return set(), set(), {'processed_ci_ids': [], 'failed_ci_ids': [], 'stats': {'images': 0, 'faces': 0, 'qdrant': 0}}


def save_cp(processed_ci, failed_ci, stats):
    data = {'processed_ci_ids': sorted(processed_ci), 'failed_ci_ids': sorted(failed_ci), 'stats': stats}
    tmp = CHECKPOINT_FILE + '.tmp'
    with open(tmp, 'w') as f:
        json.dump(data, f)
    os.replace(tmp, CHECKPOINT_FILE)


def get_images_with_pending_faces(cursor, start_id=None, max_count=None):
    """Get class_images that have face_records without face_encoding."""
    query = """
        SELECT DISTINCT ci.id, ci.image_url
        FROM class_image ci
        JOIN face_record fr ON fr.class_image_id = ci.id
        WHERE (fr.face_encoding IS NULL OR fr.face_encoding = '')
    """
    params = []
    if start_id:
        query += ' AND ci.id >= %s'
        params.append(start_id)
    query += ' ORDER BY ci.id ASC'
    if max_count:
        query += ' LIMIT %s'
        params.append(max_count)
    cursor.execute(query, params)
    return cursor.fetchall()


def get_fr_for_image(cursor, ci_id):
    """Get all face_records for a class_image, keyed by bbox center."""
    cursor.execute(
        "SELECT id, bbox FROM face_record WHERE class_image_id=%s AND (face_encoding IS NULL OR face_encoding = '')",
        (ci_id,))
    rows = cursor.fetchall()
    result = []
    for r in rows:
        try:
            b = json.loads(r['bbox'])
            cx = b['x'] + b['width'] / 2
            cy = b['y'] + b['height'] / 2
            result.append({'id': r['id'], 'bbox': b, 'cx': cx, 'cy': cy})
        except:
            pass
    return result


def resolve_path(image_url):
    if image_url.startswith('/'):
        return image_url
    if image_url.startswith('../'):
        return os.path.normpath(os.path.join(
            '/home/zebra/Downloads/官渡一中初一班-0526/emotion-platform', image_url))
    return image_url


def match_face(fr_list, fx, fy, threshold=BBOX_MATCH_THRESHOLD):
    """Find nearest face_record by bbox center distance. Returns (fr_id, distance) or None."""
    best, best_d = None, threshold
    for fr in fr_list:
        d = ((fr['cx'] - fx) ** 2 + (fr['cy'] - fy) ** 2) ** 0.5
        if d < best_d:
            best_d = d
            best = fr['id']
    return best


def restart_docker():
    log.warning('Restarting face_server...')
    import subprocess
    try:
        subprocess.run(['docker', 'restart', DOCKER_CONTAINER], capture_output=True, timeout=60)
        time.sleep(15)
        return True
    except Exception as e:
        log.error('Docker restart failed: %s', str(e)[:100])
        return False


def make_stub():
    ch = grpc.insecure_channel(FACE_SERVER,
        options=[('grpc.max_send_message_length', 50*1024*1024),
                 ('grpc.max_receive_message_length', 50*1024*1024)])
    grpc.channel_ready_future(ch).result(timeout=30)
    return ch, FaceServiceStub(ch)


def process_image(stub, image_url, fr_list):
    """Process one class_image: Analyze + match + return results."""
    path = resolve_path(image_url)
    with open(path, 'rb') as f:
        data = f.read()

    req = FaceAnalysisRequest(image_data=data, enabled_features=FEATURES)
    resp = stub.Analyze(req, timeout=GRPC_TIMEOUT)

    if not resp.success:
        return [], 'Analyze failed'

    matches = []
    for face in resp.faces:
        fb = face.feature
        if not fb or len(fb) < 512:
            continue
        fx = face.token.x + face.token.width / 2
        fy = face.token.y + face.token.height / 2
        fr_id = match_face(fr_list, fx, fy)
        if fr_id is None:
            continue
        b64 = base64.b64encode(fb).decode('ascii')
        floats = list(struct.unpack('f' * 128, fb))
        matches.append({
            'fr_id': fr_id,
            'encoding_b64': b64,
            'vector': floats,
            'confidence': face.token.confidence
        })

    return matches, None


def upsert_qdrant(points):
    if not points:
        return 0
    r = requests.put('%s/collections/%s/points?wait=true' % (QDRANT_URL, QDRANT_COLLECTION),
                     json={'points': points}, timeout=30)
    if r.status_code in (200, 201):
        return r.json().get('result', {}).get('count', len(points))
    log.warning('Qdrant error: %s', r.text[:200])
    return 0


def main():
    parser = argparse.ArgumentParser(description='Step 3: Face Feature Extraction + Qdrant Registration')
    parser.add_argument('--max', type=int, default=0, help='Max class_images to process')
    parser.add_argument('--resume', action='store_true', help='Resume from checkpoint')
    parser.add_argument('--start-id', type=int, default=0, help='Start from class_image ID')
    parser.add_argument('--batch', type=int, default=BATCH_SIZE, help='Checkpoint interval')
    args = parser.parse_args()

    log.info('Step 3: Face Feature Extraction (full-image approach)')
    ensure_qdrant()

    # Initial gRPC connection
    _channel, stub = make_stub()
    log.info('Connected to face_server')

    db = db_connect()
    cursor = db.cursor()

    processed_ci, failed_ci, cp_data = load_cp()
    stats = cp_data.get('stats', {'images': 0, 'faces': 0, 'qdrant': 0})
    img_count = stats.get('images', 0)
    face_count = stats.get('faces', 0)
    qdrant_count = stats.get('qdrant', 0)

    images = get_images_with_pending_faces(cursor, start_id=args.start_id or None, max_count=args.max or None)
    if args.resume and processed_ci:
        images = [im for im in images if im['id'] not in processed_ci]

    log.info('Processing %d class_images...', len(images))
    if not images:
        log.info('Nothing to process.')
        return

    start_time = time.time()
    save_counter = 0
    item_count = 0
    qdrant_batch = []
    stub_good = True

    for im in images:
        ci_id = im['id']
        item_count += 1
        fr_list = get_fr_for_image(cursor, ci_id)
        if not fr_list:
            processed_ci.add(ci_id)
            continue

        # Reconnect if stub was rebuilt
        if not stub_good:
            try:
                _channel, stub = make_stub()
                stub_good = True
            except:
                log.warning('Cannot reconnect to face_server, aborting')
                break

        try:
            matches, err = process_image(stub, im['image_url'], fr_list)

            if err:
                log.warning('  [%d/%d] CI #%d: %s', item_count, len(images), ci_id, err)
                failed_ci.add(ci_id)
                stats['errors'] = stats.get('errors', 0) + 1
                save_counter += 1
                continue

            # MySQL batch update
            updated = 0
            for m in matches:
                cursor.execute(
                    "UPDATE face_record SET face_encoding=%s, lib_register_status='registered', is_registered_to_lib=1 WHERE id=%s",
                    (m['encoding_b64'], m['fr_id']))
                updated += 1
                face_count += 1

                qdrant_batch.append({
                    'id': m['fr_id'],
                    'vector': m['vector'],
                    'payload': {'face_record_id': m['fr_id'], 'class_image_id': ci_id}
                })
            db.commit()

            # Flush Qdrant batch
            if len(qdrant_batch) >= 200:
                qdrant_count += upsert_qdrant(qdrant_batch)
                qdrant_batch = []

            img_count += 1
            processed_ci.add(ci_id)
            log.info('  [%d/%d] CI #%d: %d/%d faces matched', item_count, len(images), ci_id, updated, len(fr_list))

        except grpc.RpcError as e:
            code = e.code() if hasattr(e, 'code') else 'UNKNOWN'
            log.warning('  [%d/%d] CI #%d: gRPC %s, restarting server...', item_count, len(images), ci_id, code)
            if restart_docker():
                try:
                    _channel, stub = make_stub()
                    stub_good = True
                except:
                    stub_good = False
            failed_ci.add(ci_id)
            stats['errors'] = stats.get('errors', 0) + 1

        except Exception as e:
            log.warning('  [%d/%d] CI #%d: %s', item_count, len(images), ci_id, str(e)[:100])
            failed_ci.add(ci_id)
            stats['errors'] = stats.get('errors', 0) + 1

        save_counter += 1
        if save_counter >= args.batch:
            if qdrant_batch:
                qdrant_count += upsert_qdrant(qdrant_batch)
                qdrant_batch = []
            stats['images'] = img_count
            stats['faces'] = face_count
            stats['qdrant'] = qdrant_count
            save_cp(processed_ci, failed_ci, stats)
            save_counter = 0
            elapsed = time.time() - start_time
            rate = item_count / elapsed if elapsed > 0 else 0
            rem = len(images) - item_count
            eta = rem / rate if rate > 0 else 0
            log.info('Checkpoint: %d/%d CI, %d faces, %d qdrant, %.1f/s, ETA %dmin',
                     item_count, len(images), face_count, qdrant_count, rate, eta // 60 if eta > 0 else 0)

    # Final flush
    if qdrant_batch:
        qdrant_count += upsert_qdrant(qdrant_batch)
    stats['images'] = img_count
    stats['faces'] = face_count
    stats['qdrant'] = qdrant_count
    save_cp(processed_ci, failed_ci, stats)

    elapsed = time.time() - start_time
    log.info('\n=== Step 3 Complete ===')
    log.info('Images:    %d in %.1f min', item_count, elapsed / 60)
    log.info('Faces:     %d', face_count)
    log.info('Qdrant:    %d', qdrant_count)
    log.info('Failed CI: %d', len(failed_ci))
    log.info('Speed:     %.1f img/s (%.1f faces/s)',
             item_count / elapsed if elapsed > 0 else 0,
             face_count / elapsed if elapsed > 0 else 0)

    cursor.close()
    db.close()
    _channel.close()


if __name__ == '__main__':
    main()
