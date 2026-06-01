#!/usr/bin/env python3
"""
Step 1: Face Detection + Cropping (直连 face_server gRPC, 保存到 MySQL)
Usage:
  python3 process_faces_step1.py                    # 全量处理
  python3 process_faces_step1.py --max 100           # 仅处理前100张
  python3 process_faces_step1.py --resume            # 断点续传
  python3 process_faces_step1.py --start-id 5000     # 从指定ID开始
"""

import os, sys, json, time, base64, io, uuid, subprocess
from pathlib import Path
from datetime import datetime
import argparse
import logging

import grpc
from PIL import Image
import pymysql

sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import FaceAnalysisRequest
from inference_pb2_grpc import FaceServiceStub

# === Config ===
FACE_SERVER = 'localhost:50053'
DB_HOST = '192.168.3.12'
DB_PORT = 3307
DB_USER = 'root'
DB_PASS = '123456'
DB_NAME = 'emotion_platform'
DATA_ROOT = '/home/zebra/Downloads/官渡一中初一班-0526/data'
CROP_ROOT = '/home/zebra/Downloads/官渡一中初一班-0526/emotion-platform/images/cropped'
CHECKPOINT_FILE = '/tmp/face_detection_checkpoint.json'
CONFIDENCE_THRESHOLD = 0.3
CROP_MARGIN = 0.30
BATCH_SIZE = 50  # Save checkpoint every N images

# gRPC features
FEAT_DETECT = 0x01
FEAT_QUALITY = 0x20
FEAT_EMOTION = 0x80
DETECT_FEATURES = FEAT_DETECT | FEAT_QUALITY  # No emotion in step 1

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)
DB = None  # Global DB connection


def db_connect():
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=DB_NAME,
                           charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)


def get_pending_images(cursor, start_id=None, max_count=None):
    query = "SELECT id, image_url, class_id, capture_time, period_label FROM class_image WHERE status='PENDING'"
    params = []
    if start_id:
        query += " AND id >= %s"
        params.append(start_id)
    query += " ORDER BY id ASC"
    if max_count:
        query += " LIMIT %s"
        params.append(max_count)
    cursor.execute(query, params)
    return cursor.fetchall()


def load_checkpoint():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f:
            data = json.load(f)
        processed = set(data.get('processed_ids', []))
        failed = set(data.get('failed_ids', []))
        return processed, failed, data
    return set(), set(), {'processed_ids': [], 'failed_ids': [], 'stats': {'total_faces': 0, 'total_emotions': 0}}


def save_checkpoint(processed_ids, failed_ids, stats):
    data = {'processed_ids': list(processed_ids), 'failed_ids': list(failed_ids), 'stats': stats}
    tmp = CHECKPOINT_FILE + '.tmp'
    with open(tmp, 'w') as f:
        json.dump(data, f)
    os.replace(tmp, CHECKPOINT_FILE)


def crop_face(image_path, bbox, margin=CROP_MARGIN):
    x, y, w, h = bbox
    mx = int(w * margin)
    my = int(h * margin)
    with Image.open(image_path) as img:
        left = max(0, int(x) - mx)
        top = max(0, int(y) - my)
        right = min(img.width, int(x + w) + mx)
        bottom = min(img.height, int(y + h) + my)
        crop = img.crop((left, top, right, bottom))
        buf = io.BytesIO()
        crop.save(buf, 'JPEG', quality=90)
        return buf.getvalue()


def save_crop(crop_jpeg, ci_id, face_idx, capture_time, period_label):
    """Save cropped face to disk. Returns the filesystem path."""
    dt = capture_time
    date_str = dt.strftime('%Y-%m-%d') if hasattr(dt, 'strftime') else str(dt)[:10]
    period = period_label or 'other'
    rel_dir = f"官渡一中/初一班/{date_str}/{period}"
    full_dir = os.path.join(CROP_ROOT, rel_dir)
    os.makedirs(full_dir, exist_ok=True)
    filename = f"face_{ci_id}_{face_idx}.jpg"
    full_path = os.path.join(full_dir, filename)
    with open(full_path, 'wb') as f:
        f.write(crop_jpeg)
    return str(full_path)


def main():
    parser = argparse.ArgumentParser(description='Step 1: Face Detection + Cropping')
    parser.add_argument('--max', type=int, default=0, help='Max images to process')
    parser.add_argument('--resume', action='store_true', help='Resume from checkpoint')
    parser.add_argument('--start-id', type=int, default=0, help='Start from specific class_image ID')
    args = parser.parse_args()

    # Init gRPC
    log.info("Connecting to face_server...")
    channel = grpc.insecure_channel(FACE_SERVER,
        options=[('grpc.max_send_message_length', 50*1024*1024),
                 ('grpc.max_receive_message_length', 50*1024*1024)])
    stub = FaceServiceStub(channel)
    log.info("Connected.")

    # Connect to DB
    log.info("Connecting to MySQL...")
    db = db_connect()
    cursor = db.cursor()
    log.info("Connected.")

    # Load checkpoint
    processed_ids, failed_ids, cp_data = load_checkpoint()
    stats = cp_data.get('stats', {'total_faces': 0, 'total_emotions': 0})
    total_faces = stats.get('total_faces', 0)

    if args.resume:
        log.info(f"Resume mode: {len(processed_ids)} already processed, {len(failed_ids)} failed")

    # Get pending images
    images = get_pending_images(cursor, start_id=args.start_id if args.start_id > 0 else None)
    if args.max > 0:
        images = images[:args.max]

    # Filter out already processed
    if args.resume or processed_ids:
        images = [img for img in images if img['id'] not in processed_ids]

    log.info(f"Processing {len(images)} images...")
    start_time = time.time()
    save_counter = 0
    img_count = 0

    for img in images:
        ci_id = img['id']
        img_path = img['image_url']
        class_id = img['class_id']
        capture_time = img['capture_time']
        period_label = img['period_label']
        img_count += 1
        rel_name = os.path.relpath(img_path, DATA_ROOT) if img_path.startswith(DATA_ROOT) else os.path.basename(img_path)

        # Read image file
        if not os.path.exists(img_path):
            log.warning(f"  [{img_count}] Image not found: {img_path}")
            failed_ids.add(ci_id)
            continue

        try:
            with open(img_path, 'rb') as f:
                image_data = f.read()
        except Exception as e:
            log.warning(f"  [{img_count}] Cannot read {rel_name}: {e}")
            failed_ids.add(ci_id)
            continue

        # Face detection via gRPC Analyze
        face_count = 0
        max_retries = 3
        for attempt in range(max_retries):
            try:
                req = FaceAnalysisRequest(image_data=image_data, enabled_features=DETECT_FEATURES)
                resp = stub.Analyze(req, timeout=180)
                break  # Success, exit retry loop
            except Exception as e:
                err_str = str(e)
                log.warning(f"  [{img_count}] Analyze failed for #{ci_id} (attempt {attempt+1}): {err_str[:80]}")
                if attempt < max_retries - 1:
                    # Restart face_server and retry
                    log.info("  Restarting face_server container...")
                    import subprocess
                    subprocess.run(['docker', 'restart', 'docker-face-1-1'],
                                   capture_output=True, timeout=30)
                    import time
                    time.sleep(5)
                    # Re-create channel and stub
                    channel = grpc.insecure_channel(FACE_SERVER,
                        options=[('grpc.max_send_message_length', 50*1024*1024),
                                 ('grpc.max_receive_message_length', 50*1024*1024)])
                    stub = FaceServiceStub(channel)
                    log.info("  face_server restarted, retrying...")
                else:
                    failed_ids.add(ci_id)
                    break
        else:
            # All retries exhausted
            continue

        if not resp.success or len(resp.faces) == 0:
            # No faces - mark as completed with 0 faces
            cursor.execute("UPDATE class_image SET status='COMPLETED', face_detected_count=0, emotion_recognized_count=0 WHERE id=%s", (ci_id,))
            db.commit()
            processed_ids.add(ci_id)
            log.info(f"  [{img_count}] #{ci_id} {rel_name}: 0 faces")
            continue

        # Process each face
        created_records = 0
        for face_idx, face in enumerate(resp.faces):
            conf = face.token.confidence
            if conf < CONFIDENCE_THRESHOLD:
                continue

            bbox_str = json.dumps({
                'x': face.token.x, 'y': face.token.y,
                'width': face.token.width, 'height': face.token.height
            })

            try:
                bbox = (face.token.x, face.token.y, face.token.width, face.token.height)
                crop_jpeg = crop_face(img_path, bbox)

                # Save to disk
                crop_path = save_crop(crop_jpeg, ci_id, face_idx, capture_time, period_label)

                # Insert face_record
                cursor.execute("""
                    INSERT INTO face_record (class_image_id, bbox, confidence, quality,
                        cropped_image_url, status, created_at)
                    VALUES (%s, %s, %s, %s, %s, 'DETECTED', NOW())
                """, (ci_id, bbox_str, conf, face.quality if face.quality > 0 else None, crop_path))
                created_records += 1
            except Exception as e:
                log.debug(f"  Face {face_idx} crop/insert failed: {e}")

            face_count += 1

        # Mark class_image as COMPLETED
        cursor.execute("""
            UPDATE class_image SET status='COMPLETED',
                face_detected_count=%s, emotion_recognized_count=0
            WHERE id=%s
        """, (face_count, ci_id))
        db.commit()

        if face_count > 0:
            total_faces += face_count
            processed_ids.add(ci_id)
            log.info(f"  [{img_count}] #{ci_id} {rel_name}: {face_count} faces, {created_records} records")
        else:
            processed_ids.add(ci_id)

        # Save checkpoint
        save_counter += 1
        if save_counter >= BATCH_SIZE:
            stats['total_faces'] = total_faces
            save_checkpoint(processed_ids, failed_ids, stats)
            save_counter = 0
            elapsed = time.time() - start_time
            rate = img_count / elapsed if elapsed > 0 else 0
            log.info(f"Checkpoint: {img_count} images, {total_faces} faces ({rate:.1f} img/s)")

    # Final save
    stats['total_faces'] = total_faces
    save_checkpoint(processed_ids, failed_ids, stats)
    elapsed = time.time() - start_time
    log.info(f"\n=== Step 1 Complete ===")
    log.info(f"Images: {img_count} in {elapsed/60:.1f} min")
    log.info(f"Faces detected: {total_faces}")
    log.info(f"Failed: {len(failed_ids)}")

    cursor.close()
    db.close()


if __name__ == '__main__':
    main()
