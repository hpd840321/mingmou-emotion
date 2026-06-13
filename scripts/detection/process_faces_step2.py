#!/usr/bin/env python3
"""
Step 2: Emotion Recognition + Face Registration to Library
直连 emotion_server gRPC (port 50057) 进行情绪识别，可选注册到 VisionMind 图库

Usage:
  python3 process_faces_step2.py                          # 全量处理
  python3 process_faces_step2.py --max 1000               # 仅处理前 1000 条
  python3 process_faces_step2.py --resume                  # 断点续传
  python3 process_faces_step2.py --start-id 5000           # 从指定 face_record ID 开始
  python3 process_faces_step2.py --register                # 启用图库注册
  python3 process_faces_step2.py --register-only           # 仅图库注册（跳过情绪）
  python3 process_faces_step2.py --resume --max 500        # 续传+限速调试
"""

import os, sys, json, time, math, base64
from pathlib import Path
from datetime import datetime
import argparse
import logging

import grpc
import pymysql

sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import EmotionRequest
from inference_pb2_grpc import EmotionServiceStub

# === Config ===
EMOTION_SERVER = 'localhost:50057'
REGISTER_API = 'http://localhost:8080/v1/facedb/register'
DB_HOST = '192.168.3.12'
DB_PORT = 3307
DB_USER = 'root'
DB_PASS = '123456'
DB_NAME = 'emotion_platform'
CHECKPOINT_FILE = '/tmp/face_emotion_checkpoint.json'
BATCH_SIZE = 500        # Checkpoint every N face records
MAX_RETRIES = 3
GRPC_TIMEOUT = 30       # Seconds per emotion request

# Emotion class indices from EmotiEffLib (8-class: 0=angry .. 7=surprise)
EMOTION_KEYS = ['angry', 'contempt', 'disgust', 'fear', 'happy', 'neutral', 'sad', 'surprise']

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)


# ============================================================
#  DB helpers
# ============================================================

def db_connect():
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=DB_NAME,
                           charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)


def get_face_records_to_process(cursor, start_id=None, max_count=None):
    """
    Fetch face_records that have no emotion_record yet, ordered by ID.
    Optionally skip those with missing cropped files (filesystem check done in main loop).
    """
    query = """
        SELECT fr.id, fr.class_image_id, fr.cropped_image_url, fr.confidence, fr.quality,
               ci.class_id, ci.capture_time, ci.period_label
        FROM face_record fr
        JOIN class_image ci ON ci.id = fr.class_image_id
        LEFT JOIN emotion_record er ON er.face_record_id = fr.id
        WHERE er.id IS NULL
    """
    params = []
    if start_id:
        query += " AND fr.id >= %s"
        params.append(start_id)
    query += " ORDER BY fr.id ASC"
    if max_count:
        query += " LIMIT %s"
        params.append(max_count)
    cursor.execute(query, params)
    return cursor.fetchall()


def get_face_records_to_register(cursor, start_id=None, max_count=None):
    """
    Fetch face_records that are IDENTIFIED (have emotion) but not yet registered to library.
    """
    query = """
        SELECT fr.id, fr.class_image_id, fr.cropped_image_url, fr.confidence, fr.quality,
               ci.class_id, ci.capture_time, ci.period_label
        FROM face_record fr
        JOIN class_image ci ON ci.id = fr.class_image_id
        WHERE fr.status = 'IDENTIFIED'
          AND (fr.lib_register_status IS NULL OR fr.lib_register_status = 'pending')
    """
    params = []
    if start_id:
        query += " AND fr.id >= %s"
        params.append(start_id)
    query += " ORDER BY fr.id ASC"
    if max_count:
        query += " LIMIT %s"
        params.append(max_count)
    cursor.execute(query, params)
    return cursor.fetchall()


# ============================================================
#  Checkpoint
# ============================================================

def load_checkpoint():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f:
            data = json.load(f)
        processed = set(data.get('processed_ids', []))
        failed = set(data.get('failed_ids', []))
        registered = set(data.get('registered_ids', []))
        return processed, failed, registered, data
    return set(), set(), set(), {
        'processed_ids': [],
        'failed_ids': [],
        'registered_ids': [],
        'stats': {'emotions': 0, 'registrations': 0}
    }


def save_checkpoint(processed_ids, failed_ids, registered_ids, stats):
    data = {
        'processed_ids': sorted(processed_ids),
        'failed_ids': sorted(failed_ids),
        'registered_ids': sorted(registered_ids),
        'stats': stats
    }
    tmp = CHECKPOINT_FILE + '.tmp'
    with open(tmp, 'w') as f:
        json.dump(data, f)
    os.replace(tmp, CHECKPOINT_FILE)


# ============================================================
#  Math helpers
# ============================================================

def softmax(logits):
    """Convert logits to probabilities (0-1 range, sum to 1)."""
    max_logit = max(logits)
    exps = [math.exp(v - max_logit) for v in logits]
    sum_exps = sum(exps)
    return [e / sum_exps for e in exps]


# ============================================================
#  Emotion Recognition via gRPC EmotionService
# ============================================================

def analyze_emotion(stub, image_data):
    """
    Call EmotionService.Predict, returns (success, emotion_dict, dominant_label, dominant_conf)
    emotion_dict: {'happy': 0.85, 'sad': 0.02, ...} (probabilities, softmax applied)
    """
    req = EmotionRequest(image_data=image_data)
    resp = stub.Predict(req, timeout=GRPC_TIMEOUT)

    if not resp.success:
        return False, None, None, None

    if not resp.HasField('emotion'):
        return False, None, None, None

    e = resp.emotion
    raw_logits = list(e.probabilities)

    if not raw_logits or len(raw_logits) < 8:
        return False, None, None, None

    # Truncate/pad to 8
    logits_8 = raw_logits[:8]
    while len(logits_8) < 8:
        logits_8.append(-10.0)

    # Softmax to get probabilities
    probs = softmax(logits_8)

    # Build named dict
    emotion_dict = {}
    for i, key in enumerate(EMOTION_KEYS):
        emotion_dict[key] = probs[i] if i < len(probs) else 0.0

    # Dominant emotion
    max_idx = max(range(len(probs)), key=lambda i: probs[i])
    dominant_label = EMOTION_KEYS[max_idx]
    dominant_conf = probs[max_idx]

    log.debug(f"Emotion: dominant={dominant_label} ({dominant_conf:.2%}) "
              f"probs={ {k: round(v, 3) for k, v in emotion_dict.items()} }")

    return True, emotion_dict, dominant_label, dominant_conf


# ============================================================
#  Face Registration via REST API
# ============================================================

def register_face(face_record_id, cropped_image_path, class_id):
    """
    Register a face to the VisionMind face library via REST API.
    Returns (success, lib_face_id, error_message).
    """
    import requests

    if not os.path.exists(cropped_image_path):
        return False, None, "Cropped image not found"

    try:
        with open(cropped_image_path, 'rb') as f:
            image_bytes = f.read()
        base64_data = base64.b64encode(image_bytes).decode('ascii')
        data_uri = "data:image/jpeg;base64," + base64_data

        face_id = f"face_step2_{face_record_id}"

        payload = {
            "id": face_id,
            "name": face_id,
            "extra": json.dumps({"face_record_id": face_record_id, "class_id": class_id}),
            "image_base64": base64_data,
            "image": data_uri
        }

        resp = requests.post(REGISTER_API, json=payload, timeout=15)
        result = resp.json()
        code = result.get('code', 1)

        if code == 0:
            return True, face_id, None
        else:
            msg = result.get('message', 'unknown error')
            return False, None, msg

    except Exception as e:
        return False, None, str(e)


# ============================================================
#  Insert emotion_record to DB
# ============================================================

def insert_emotion_record(cursor, db, face_record_id, emotion_dict, dominant_label, dominant_conf):
    """Insert emotion_record and update face_record status to IDENTIFIED."""
    cursor.execute("""
        INSERT INTO emotion_record
            (face_record_id, emotion_happy, emotion_sad, emotion_angry,
             emotion_surprise, emotion_fear, emotion_disgust, emotion_neutral,
             emotion_contempt,
             dominant_emotion, dominant_confidence, created_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, NOW())
    """, (
        face_record_id,
        emotion_dict.get('happy'),
        emotion_dict.get('sad'),
        emotion_dict.get('angry'),
        emotion_dict.get('surprise'),
        emotion_dict.get('fear'),
        emotion_dict.get('disgust'),
        emotion_dict.get('neutral'),
        emotion_dict.get('contempt'),
        dominant_label,
        dominant_conf
    ))
    cursor.execute("""
        UPDATE face_record SET status='IDENTIFIED' WHERE id=%s
    """, (face_record_id,))
    db.commit()


def update_registration_status(cursor, db, face_record_id, success, lib_face_id=None, error=None):
    """Update face_record with registration result."""
    if success:
        cursor.execute("""
            UPDATE face_record SET
                lib_face_id=%s,
                is_registered_to_lib=1,
                lib_register_status='registered',
                registered_at=NOW()
            WHERE id=%s
        """, (lib_face_id, face_record_id))
    else:
        cursor.execute("""
            UPDATE face_record SET
                lib_register_status='failed',
                error_message=%s
            WHERE id=%s
        """, (error[:500] if error else 'unknown error', face_record_id))
    db.commit()


# ============================================================
#  Main
# ============================================================

def register_one(fr_id, img_path, class_id):
    """Thread-safe registration wrapper."""
    success, lib_id, error = register_face(fr_id, img_path, class_id)
    return fr_id, success, lib_id, error


def main():
    parser = argparse.ArgumentParser(description='Step 2: Emotion Recognition + Face Registration')
    parser.add_argument('--max', type=int, default=0, help='Max face records to process')
    parser.add_argument('--resume', action='store_true', help='Resume from checkpoint')
    parser.add_argument('--start-id', type=int, default=0, help='Start from specific face_record ID')
    parser.add_argument('--register', action='store_true', help='Enable face registration to library')
    parser.add_argument('--register-only', action='store_true', help='Only do registration (skip emotion)')
    parser.add_argument('--batch', type=int, default=BATCH_SIZE, help=f'Checkpoint interval (default: {BATCH_SIZE})')
    parser.add_argument('--parallel', type=int, default=1, help='Parallel workers for registration (default: 1)')
    args = parser.parse_args()

    # Validate args
    do_emotion = not args.register_only
    do_register = args.register or args.register_only
    if args.register_only:
        do_emotion = False
        log.info("Mode: REGISTER ONLY")

    # Connect to gRPC EmotionService
    if do_emotion:
        log.info(f"Connecting to emotion_server at {EMOTION_SERVER}...")
        channel = grpc.insecure_channel(EMOTION_SERVER,
            options=[('grpc.max_send_message_length', 50*1024*1024),
                     ('grpc.max_receive_message_length', 50*1024*1024)])
        stub = EmotionServiceStub(channel)
        # Quick health check
        try:
            stub.Predict(EmotionRequest(image_data=b'\xff\xd8\xff\xe0'), timeout=10)
        except Exception as e:
            log.warning(f"emotion_server health check: {str(e)[:80]}")
        log.info("emotion_server connected.")
    else:
        stub = None

    # Connect to DB
    log.info("Connecting to MySQL...")
    db = db_connect()
    cursor = db.cursor()
    log.info("Connected.")

    # Load checkpoint
    processed_ids, failed_ids, registered_ids, cp_data = load_checkpoint()
    stats = cp_data.get('stats', {'emotions': 0, 'registrations': 0})
    emotion_count = stats.get('emotions', 0)
    register_count = stats.get('registrations', 0)

    if args.resume:
        log.info(f"Resume mode: {len(processed_ids)} emotions, {len(registered_ids)} registrations, {len(failed_ids)} failed")

    # Determine which records to process
    if do_emotion:
        records = get_face_records_to_process(cursor,
            start_id=args.start_id if args.start_id > 0 else None)
    else:
        records = get_face_records_to_register(cursor,
            start_id=args.start_id if args.start_id > 0 else None)

    if args.max > 0:
        records = records[:args.max]

    # Filter out already-processed from checkpoint
    if args.resume:
        if do_emotion:
            records = [r for r in records if r['id'] not in processed_ids]
        if do_register:
            records = [r for r in records if r['id'] not in registered_ids]

    log.info(f"Processing {len(records)} face records...")
    start_time = time.time()
    save_counter = 0
    item_count = 0
    error_count = 0
    skip_count = 0

    for rec in records:
        fr_id = rec['id']
        img_path = rec['cropped_image_url']
        class_id = rec.get('class_id') or 0
        item_count += 1

        # File existence check
        if not img_path or not os.path.exists(img_path):
            log.warning(f"  [{item_count}] FR #{fr_id}: cropped image not found: {img_path}")
            failed_ids.add(fr_id)
            save_counter += 1
            continue

        # ---- Emotion Recognition ----
        if do_emotion:
            for attempt in range(MAX_RETRIES):
                try:
                    with open(img_path, 'rb') as f:
                        image_data = f.read()

                    if len(image_data) < 100:
                        log.warning(f"  [{item_count}] FR #{fr_id}: image too small ({len(image_data)}B), skipping")
                        skip_count += 1
                        failed_ids.add(fr_id)
                        break

                    success, emotion_dict, dominant, dominant_conf = analyze_emotion(stub, image_data)

                    if success and emotion_dict:
                        insert_emotion_record(cursor, db, fr_id, emotion_dict, dominant, dominant_conf)
                        emotion_count += 1
                        processed_ids.add(fr_id)
                        log.info(f"  [{item_count}] FR #{fr_id}: {dominant} ({dominant_conf:.1%})")
                        break
                    else:
                        log.warning(f"  [{item_count}] FR #{fr_id}: emotion failed (attempt {attempt+1})")
                        if attempt < MAX_RETRIES - 1:
                            time.sleep(2)
                        else:
                            failed_ids.add(fr_id)
                except Exception as e:
                    err_str = str(e)[:100]
                    log.warning(f"  [{item_count}] FR #{fr_id}: error (attempt {attempt+1}): {err_str}")
                    if attempt < MAX_RETRIES - 1:
                        time.sleep(3)
                    else:
                        failed_ids.add(fr_id)
        # ---- Parallel Registration ----
        elif do_register and args.parallel > 1:
            # Defer to batch processing below
            pass

        # ---- Sequential Registration ----
        elif do_register:
            log.info(f"  [{item_count}] FR #{fr_id}: registering to library...")
            success, lib_id, error = register_face(fr_id, img_path, class_id)
            if success:
                update_registration_status(cursor, db, fr_id, True, lib_id=lib_id)
                register_count += 1
                registered_ids.add(fr_id)
                log.info(f"  [{item_count}] FR #{fr_id}: registered OK (lib_id={lib_id})")
            else:
                update_registration_status(cursor, db, fr_id, False, error=error)
                log.warning(f"  [{item_count}] FR #{fr_id}: register failed: {error}")
                registered_ids.add(fr_id)

        # ---- Parallel registration batch ----
        if do_register and args.parallel > 1 and not do_emotion:
            # For register-only mode with parallel workers, process in batches
            from concurrent.futures import ThreadPoolExecutor, as_completed

            batch_size = args.parallel * 4  # Process in groups
            for batch_start in range(0, len(records), batch_size):
                batch = records[batch_start:batch_start + batch_size]
                log.info(f"Parallel batch: {batch_start+1}~{batch_start+len(batch)} / {len(records)}")

                with ThreadPoolExecutor(max_workers=args.parallel) as executor:
                    futures = {}
                    for rec in batch:
                        fr_id_b = rec['id']
                        img_path_b = rec['cropped_image_url']
                        class_id_b = rec.get('class_id') or 0
                        if img_path_b and os.path.exists(img_path_b):
                            futures[executor.submit(register_one, fr_id_b, img_path_b, class_id_b)] = fr_id_b

                    for future in as_completed(futures):
                        fr_id_b, success, lib_id, error = future.result()
                        db_local = db_connect()
                        cur_local = db_local.cursor()
                        try:
                            if success:
                                update_registration_status(cur_local, db_local, fr_id_b, True, lib_face_id=lib_id)
                                register_count += 1
                                registered_ids.add(fr_id_b)
                            else:
                                update_registration_status(cur_local, db_local, fr_id_b, False, error=error)
                                registered_ids.add(fr_id_b)
                        finally:
                            cur_local.close()
                            db_local.close()

                        item_count += 1
                        save_counter += 1
                        if save_counter >= args.batch:
                            stats['emotions'] = emotion_count
                            stats['registrations'] = register_count
                            save_checkpoint(processed_ids, failed_ids, registered_ids, stats)
                            save_counter = 0

            # Skip remaining sequential loop by jumping to final save
            # (records already fully processed)
            stats['emotions'] = emotion_count
            stats['registrations'] = register_count
            save_checkpoint(processed_ids, failed_ids, registered_ids, stats)
            elapsed = time.time() - start_time
            log.info(f"\n=== Parallel Registration Complete ===")
            log.info(f"Total: {item_count} in {elapsed/60:.1f} min")
            log.info(f"Registrations: {register_count}")
            cursor.close()
            db.close()
            return

        # ---- Registration after emotion (--register mode) ----
        if do_emotion and do_register and fr_id not in failed_ids:
            # After emotion success, also register
            try:
                success, lib_id, error = register_face(fr_id, img_path, class_id)
                if success:
                    update_registration_status(cursor, db, fr_id, True, lib_id=lib_id)
                    register_count += 1
                    registered_ids.add(fr_id)
                    log.debug(f"  FR #{fr_id}: registered OK")
                else:
                    log.debug(f"  FR #{fr_id}: register skipped: {error}")
            except Exception as e:
                log.debug(f"  FR #{fr_id}: register error: {str(e)[:100]}")

        # ---- Checkpoint ----
        save_counter += 1
        if save_counter >= args.batch:
            stats['emotions'] = emotion_count
            stats['registrations'] = register_count
            save_checkpoint(processed_ids, failed_ids, registered_ids, stats)
            save_counter = 0
            elapsed = time.time() - start_time
            rate = item_count / elapsed if elapsed > 0 else 0
            log.info(f"Checkpoint: {item_count} items, {emotion_count} emotions, "
                     f"{register_count} registrations, {error_count} errors ({rate:.1f}/s)")

    # ---- Final save ----
    stats['emotions'] = emotion_count
    stats['registrations'] = register_count
    save_checkpoint(processed_ids, failed_ids, registered_ids, stats)

    elapsed = time.time() - start_time
    log.info(f"\n=== Step 2 {'Emotion' if do_emotion else 'Registration'} Complete ===")
    log.info(f"Total items: {item_count} in {elapsed/60:.1f} min")
    log.info(f"Emotions:     {emotion_count}")
    log.info(f"Registrations: {register_count}")
    log.info(f"Failed:       {len(failed_ids)}")
    log.info(f"Skipped:      {skip_count}")
    if elapsed > 0:
        log.info(f"Speed:        {item_count/elapsed:.1f} items/s")

    cursor.close()
    db.close()
    if do_emotion and stub:
        channel.close()


if __name__ == '__main__':
    main()
