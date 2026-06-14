#!/usr/bin/env python3
"""
明眸·校园学生状态感知平台 — 数据初始化管线脚本
从 data/ 目录读取全景课堂照片，通过 face_server gRPC 进行：
  1. 人脸检测 (gRPC Analyze → face_server:50053)
  2. 人脸裁剪 (本地 Pillow)
  3. 情绪识别 (gRPC Analyze)
  4. 人脸注册到 VisionMind 人脸库
  5. 1:N 人脸搜索匹配（同一人跨图片/时段自动聚类）
  6. 写入 PostgreSQL (class_image + face_record + emotion_record + student_id 关联)

用法:
  python3 init_data_pipeline.py                    # 全量处理（检测+注册+匹配）
  python3 init_data_pipeline.py --max-images 100   # 仅处理前100张
  python3 init_data_pipeline.py --resume           # 断点续传
  python3 init_data_pipeline.py --start-id 500     # 从指定索引开始
  python3 init_data_pipeline.py --dry-run          # 干跑，只打印不写入
  python3 init_data_pipeline.py --match-only       # 仅对已存在 face_record 做匹配（不重新检测）
"""

import os, sys, json, time, base64, re, requests
from pathlib import Path
from datetime import datetime
from io import BytesIO
import argparse
import logging

import grpc
from PIL import Image
import psycopg2
import psycopg2.extras

sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import FaceAnalysisRequest
from inference_pb2_grpc import FaceServiceStub

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s %(message)s')
log = logging.getLogger('init_pipeline')

# ============================================================
#  配置
# ============================================================

VM_API = os.environ.get('VM_API', 'http://localhost:8080')
GRPC_HOST = os.environ.get('GRPC_HOST', 'localhost:50053')
DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')

DATA_ROOT = Path('/media/zebra/data/官渡一中初一班-0526/data')
CHECKPOINT_FILE = '/tmp/init_pipeline_checkpoint.json'
PERSON_REGISTRY_FILE = '/tmp/init_pipeline_persons.json'

# Detection config
CONFIDENCE_THRESHOLD = 0.3
CROP_MARGIN = 0.30
GRPC_TIMEOUT = 180
FACE_SEARCH_THRESHOLD = 0.6   # VisionMind 1:N 搜索阈值
TOP_K = 1                     # 搜索返回 top-1 即可

# Batch config
BATCH_SIZE = 50
CROP_OUTPUT_DIR = Path('/media/zebra/data/官渡一中初一班-0526/emotion-platform/images/cropped')

# ============================================================
#  VisionMind REST API 交互
# ============================================================

def vm_register_face(person_id, image_bytes, extra_json='{}'):
    """注册人脸到 VisionMind 人脸库。person_id 如 'person_1'"""
    b64 = base64.b64encode(image_bytes).decode()
    try:
        r = requests.post(f'{VM_API}/v1/facedb/register', json={
            'id': person_id,
            'name': person_id,
            'extra': extra_json,
            'image': b64,
            'image_base64': b64,
        }, timeout=30)
        if r.status_code == 409:
            log.warning('  Person %s already registered, re-registering...', person_id)
            # Re-register to update face image
            requests.post(f'{VM_API}/v1/facedb/register', json={
                'id': person_id, 'name': person_id, 'extra': extra_json,
                'image': b64, 'image_base64': b64,
                'action': 'update',
            }, timeout=30)
        return r.ok
    except Exception as e:
        log.warning('  Register failed for %s: %s', person_id, e)
        return False

def vm_search_face(image_bytes, threshold=FACE_SEARCH_THRESHOLD, top_k=TOP_K):
    """1:N 搜索人脸，返回 [(person_id, similarity), ...] 或空列表"""
    b64 = base64.b64encode(image_bytes).decode()
    try:
        r = requests.post(f'{VM_API}/v1/face/search', json={
            'image': b64,
            'image_base64': b64,
            'top_k': top_k,
            'threshold': threshold,
        }, timeout=30)
        if not r.ok:
            return []
        data = r.json()
        results = data.get('data', {}).get('results', [])
        return [(res['id'], res.get('similarity', 0)) for res in results]
    except Exception as e:
        log.warning('  Search failed: %s', e)
        return []

# ============================================================
#  人物注册表管理（持久化 person_id → student_id 映射）
# ============================================================

def load_person_registry():
    """加载人物注册表。结构：
    {
        "next_person_id": 5,
        "persons": {
            "person_1": {"student_id": null, "face_count": 150, "avg_similarity": 0.85},
            "person_2": {"student_id": 1, "face_count": 120, "avg_similarity": 0.82},
        }
    }
    """
    try:
        with open(PERSON_REGISTRY_FILE, 'r') as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return {'next_person_id': 1, 'persons': {}}

def save_person_registry(registry):
    with open(PERSON_REGISTRY_FILE, 'w') as f:
        json.dump(registry, f, indent=2)

def get_or_create_person(registry, matched_id=None):
    """获取或创建人员ID。如果 matched_id 提供则使用已有 person_id。"""
    if matched_id:
        # matched_id 可能是 'person_1' 格式或是任意字符串
        if matched_id.startswith('person_'):
            registry['persons'].setdefault(matched_id, {'student_id': None, 'face_count': 0, 'avg_similarity': 0.0})
            return matched_id
    pid = f'person_{registry["next_person_id"]}'
    registry['persons'][pid] = {'student_id': None, 'face_count': 0, 'avg_similarity': 0.0}
    registry['next_person_id'] += 1
    return pid

def update_person_stats(registry, person_id, similarity):
    p = registry['persons'][person_id]
    old_total = p['face_count']
    new_total = old_total + 1
    p['avg_similarity'] = (p['avg_similarity'] * old_total + similarity) / new_total if old_total > 0 else similarity
    p['face_count'] = new_total

# ============================================================
#  数据库
# ============================================================

def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)

def ensure_seed_data(cur):
    """Ensure seed grade(1) and class(1) exist."""
    cur.execute("INSERT INTO grade (id, name, sort_order) VALUES (1, '初一', 1) ON CONFLICT (id) DO NOTHING")
    cur.execute("""INSERT INTO class (id, grade_id, name, sort_order)
                   VALUES (1, 1, '初一班', 1) ON CONFLICT (id) DO NOTHING""")
    cur.connection.commit()

def get_student_map(cur):
    """返回 {student_no: student_id} 映射"""
    cur.execute("SELECT id, student_no FROM student")
    return {row[1]: row[0] for row in cur.fetchall()}

def insert_class_image(cur, class_id, image_url, capture_time, period_label, status='PENDING'):
    """Insert or get existing class_image. Returns (id, was_inserted)."""
    cur.execute("SELECT id FROM class_image WHERE image_url = %s", (image_url,))
    row = cur.fetchone()
    if row:
        return row[0], False
    if capture_time is None:
        capture_time = datetime.now()
    cur.execute("""INSERT INTO class_image (class_id, image_url, capture_time, period_label, status, source)
                   VALUES (%s, %s, %s, %s, %s, 'auto_scan') RETURNING id""",
                (class_id, image_url, capture_time, period_label, status))
    ci_id = cur.fetchone()[0]
    return ci_id, True

def insert_face_record(cur, class_image_id, bbox_json, confidence, quality=None,
                       student_id=None, person_id=None, cropped_image_url=None, status='DETECTED'):
    """Insert face_record with optional student and person linkage."""
    cur.execute("""INSERT INTO face_record
                   (class_image_id, bbox, confidence, quality, student_id,
                    cropped_image_url, lib_face_id, lib_register_status, status)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id""",
                (class_image_id, bbox_json, confidence, quality, student_id,
                 cropped_image_url, person_id,
                 'registered' if person_id else 'pending',
                 status))
    return cur.fetchone()[0]

def update_face_record_person(cur, face_record_id, person_id, student_id=None):
    """更新 face_record 的 person 关联和 student_id"""
    cur.execute("""UPDATE face_record
                   SET lib_face_id = %s,
                       lib_register_status = 'registered',
                       student_id = COALESCE(%s, student_id)
                   WHERE id = %s""",
                (person_id, student_id, face_record_id))

def insert_emotion_record(cur, face_record_id, dominant_emotion, dominant_confidence,
                           probs_list, dominant_state=None):
    """Insert emotion_record."""
    probs = probs_list if probs_list else [None] * 7
    cur.execute("""INSERT INTO emotion_record
                   (face_record_id, dominant_emotion, dominant_confidence,
                    emotion_neutral, emotion_happy, emotion_sad,
                    emotion_surprise, emotion_fear, emotion_disgust, emotion_angry,
                    dominant_state)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id""",
                (face_record_id, dominant_emotion, dominant_confidence,
                 probs[0] if len(probs) > 0 else None,
                 probs[1] if len(probs) > 1 else None,
                 probs[2] if len(probs) > 2 else None,
                 probs[3] if len(probs) > 3 else None,
                 probs[4] if len(probs) > 4 else None,
                 probs[5] if len(probs) > 5 else None,
                 probs[6] if len(probs) > 6 else None,
                 dominant_state))
    return cur.fetchone()[0]

# ============================================================
#  gRPC 引擎调用
# ============================================================

def get_grpc_stub():
    channel = grpc.insecure_channel(GRPC_HOST,
        options=[('grpc.max_send_message_length', 50*1024*1024),
                 ('grpc.max_receive_message_length', 50*1024*1024)])
    return FaceServiceStub(channel)

def call_detect_grpc(image_bytes):
    """Call face_server Analyze with DETECT+QUALITY+EMOTION features."""
    try:
        stub = get_grpc_stub()
        req = FaceAnalysisRequest(
            image_data=image_bytes,
            enabled_features=0x01 | 0x20 | 0x80 | 0x10)
        resp = stub.Analyze(req, timeout=GRPC_TIMEOUT)
        if not resp.success:
            log.warning("  gRPC Analyze failed: %s", resp.error_message)
            return []
        labels = ['中性', '开心', '伤心', '惊讶', '恐惧', '厌恶', '愤怒']
        faces = []
        for f in resp.faces:
            tok = f.token
            face = {
                'bbox': [int(tok.x), int(tok.y), int(tok.width), int(tok.height)],
                'confidence': tok.confidence,
                'quality': f.quality,
            }
            if f.HasField('emotion'):
                idx = f.emotion.emotion
                face['emotion_index'] = idx
                face['emotion_label'] = labels[idx] if 0 <= idx < len(labels) else '未知'
            faces.append(face)
        return faces
    except Exception as e:
        log.warning("  gRPC detect error: %s", e)
        return []

# ============================================================
#  图片处理
# ============================================================

def crop_face(image_bytes, bbox, margin=CROP_MARGIN):
    """Crop face from image using bbox [x, y, w, h] with margin. Returns JPEG bytes or None."""
    try:
        img = Image.open(BytesIO(image_bytes))
        x, y, w, h = [int(v) for v in bbox]
        mx = max(1, int(w * margin))
        my = max(1, int(h * margin))
        x1 = max(0, x - mx)
        y1 = max(0, y - my)
        x2 = min(img.width - 1, x + w + mx)
        y2 = min(img.height - 1, y + h + my)
        if x2 <= x1 or y2 <= y1:
            return None
        crop = img.crop((x1, y1, x2, y2))
        buf = BytesIO()
        crop.save(buf, 'JPEG', quality=95)
        return buf.getvalue()
    except Exception as e:
        log.warning("  Crop failed: %s", e)
        return None

def save_crop_to_disk(crop_bytes, school, class_name, date, period, face_record_id):
    """将裁剪人脸保存到磁盘，返回 URL 路径"""
    period_safe = re.sub(r'[\\/:*?"<>|]', '_', period)
    img_dir = CROP_OUTPUT_DIR / school / class_name / date / period_safe
    img_dir.mkdir(parents=True, exist_ok=True)
    output_path = img_dir / f'face_{face_record_id}.jpg'
    with open(output_path, 'wb') as f:
        f.write(crop_bytes)
    # 返回相对路径（以 /images/cropped/ 开头）
    relative = f'/images/cropped/{school}/{class_name}/{date}/{period_safe}/face_{face_record_id}.jpg'
    return relative

def parse_filename_datetime(filename):
    """Try to extract datetime from filename like 20260525135429_*.jpg"""
    m = re.match(r'(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})', filename)
    if m:
        return datetime(int(m.group(1)), int(m.group(2)), int(m.group(3)),
                       int(m.group(4)), int(m.group(5)), int(m.group(6)))
    return None

def map_to_dominant_state(emotion_label):
    """Map VisionMind Chinese emotion label to 3-state."""
    mapping = {
        '中性': 'ENGAGED', '开心': 'ENGAGED', '惊讶': 'ENGAGED',
        '伤心': 'WITHDRAWN', '恐惧': 'WITHDRAWN',
        '厌恶': 'CONFUSED', '愤怒': 'CONFUSED',
    }
    return mapping.get(emotion_label, 'UNKNOWN')

# ============================================================
#  匹配已有的 face_record（student_id 为 NULL 的记录）
# ============================================================

def match_existing_faces(dry_run=False):
    """对数据库中已存在但 student_id IS NULL 的 face_record 进行人脸匹配"""
    conn = db_connect()
    cur = conn.cursor()

    # 加载已有 person 注册表
    registry = load_person_registry()
    log.info("Loaded person registry: %d persons, next_id=%s",
             len(registry['persons']), registry['next_person_id'])

    # 获取已有的 student_id→person_id 映射（从已关联的记录）
    cur.execute("""
        SELECT DISTINCT fr.student_id, fr.lib_face_id
        FROM face_record fr
        WHERE fr.student_id IS NOT NULL AND fr.lib_face_id IS NOT NULL
    """)
    for student_id, person_id in cur.fetchall():
        if person_id and person_id.startswith('person_'):
            registry['persons'].setdefault(person_id, {'student_id': None, 'face_count': 0, 'avg_similarity': 0.0})
            registry['persons'][person_id]['student_id'] = student_id

    # 获取所有未关联的 face_record（有 bbox 信息才能重新裁剪）
    cur.execute("""
        SELECT fr.id, fr.class_image_id, fr.bbox, ci.image_url,
               ci.capture_time, fr.lib_face_id
        FROM face_record fr
        JOIN class_image ci ON ci.id = fr.class_image_id
        WHERE fr.student_id IS NULL
          AND fr.bbox IS NOT NULL
          AND fr.lib_face_id IS NULL
        ORDER BY fr.id
    """)
    rows = cur.fetchall()
    total = len(rows)
    log.info("Found %d unmatched face_records to process", total)

    if total == 0:
        log.info("No unmatched face_records found. All done!")
        return

    stats = {'matched': 0, 'registered': 0, 'errors': 0, 'skipped': 0,
             'start_time': time.time()}

    for idx, (fr_id, ci_id, bbox_json, img_url, capture_time, existing_pid) in enumerate(rows):
        if idx % 100 == 0 and idx > 0:
            elapsed = time.time() - stats['start_time']
            rate = idx / elapsed if elapsed > 0 else 0
            log.info("--- Progress: %d/%d faces, %.1f faces/min, matched=%d registered=%d ---",
                     idx, total, rate * 60, stats['matched'], stats['registered'])

        # 跳过已处理的
        if existing_pid and existing_pid.startswith('person_'):
            stats['skipped'] += 1
            continue

        # 从原图按 bbox 裁剪
        try:
            with open(img_url, 'rb') as f:
                image_bytes = f.read()
        except Exception as e:
            log.warning("  Cannot read image %s: %s", img_url, e)
            stats['errors'] += 1
            continue

        bbox = json.loads(bbox_json) if isinstance(bbox_json, str) else bbox_json
        crop_bytes = crop_face(image_bytes,
                               [bbox.get('x', 0), bbox.get('y', 0),
                                bbox.get('width', 0), bbox.get('height', 0)])
        if crop_bytes is None:
            stats['skipped'] += 1
            continue

        # VisionMind 1:N 搜索
        matches = vm_search_face(crop_bytes)
        person_id = None
        similarity = 0.0

        if matches:
            matched_id, similarity = matches[0]
            person_id = get_or_create_person(registry, matched_id=matched_id)
            update_person_stats(registry, person_id, similarity)
            stats['matched'] += 1
            log.debug("  Face %d matched to %s (sim=%.3f)", fr_id, person_id, similarity)
        else:
            # 新面孔：注册到人脸库
            person_id = get_or_create_person(registry)
            # 保存裁剪图到磁盘
            school = '官渡一中'
            class_name = '初一班'
            date_str = capture_time.strftime('%Y-%m-%d') if capture_time else 'unknown'
            period = 'other'
            try:
                # 从 DB 获取 period_label
                cur2 = conn.cursor()
                cur2.execute("SELECT period_label FROM class_image WHERE id = %s", (ci_id,))
                row2 = cur2.fetchone()
                if row2 and row2[0]:
                    period = row2[0]
                cur2.close()
            except:
                pass
            crop_url = save_crop_to_disk(crop_bytes, school, class_name, date_str, period, fr_id)
            reg_ok = vm_register_face(person_id, crop_bytes,
                                      extra_json=json.dumps({'face_record_id': fr_id}))
            if reg_ok:
                stats['registered'] += 1
            else:
                log.warning("  Register failed for face %d", fr_id)
            stats['registered'] += 1

        # 更新数据库
        if not dry_run:
            update_face_record_person(cur, fr_id, person_id, student_id=None)
            # 保存裁剪图 URL（如果之前没保存过）
            if crop_bytes and existing_pid is None:
                school = '官渡一中'
                class_name = '初一班'
                date_str = capture_time.strftime('%Y-%m-%d') if capture_time else 'unknown'
                period = 'other'
                crop_url = save_crop_to_disk(crop_bytes, school, class_name, date_str, period, fr_id)
                cur.execute("UPDATE face_record SET cropped_image_url = %s WHERE id = %s",
                           (crop_url, fr_id))
            conn.commit()

        # 保存 person registry 每 200 条
        if idx > 0 and idx % 200 == 0:
            save_person_registry(registry)

    # 最终统计
    elapsed = time.time() - stats['start_time']
    save_person_registry(registry)
    log.info("=" * 60)
    log.info("人脸匹配完成!")
    log.info("  处理: %d 张人脸", total)
    log.info("  匹配: %d (已识别)", stats['matched'])
    log.info("  注册: %d (新面孔)", stats['registered'])
    log.info("  错误: %d", stats['errors'])
    log.info("  耗时: %.0fs (%.1f faces/min)", elapsed, (total / elapsed * 60) if elapsed > 0 else 0)
    log.info("  Person 注册表: %d 人", len(registry['persons']))
    log.info("=" * 60)

    # 输出各 person 统计
    persons_sorted = sorted(registry['persons'].items(), key=lambda x: x[1]['face_count'], reverse=True)
    log.info("Top persons (by face count):")
    for pid, info in persons_sorted[:10]:
        sid = info.get('student_id')
        sid_str = f"→ student_{sid}" if sid else "(unmatched)"
        log.info("  %s: %d faces, avg_sim=%.3f %s", pid, info['face_count'], info['avg_similarity'], sid_str)

    cur.close()
    conn.close()

# ============================================================
#  主流程（检测 + 注册 + 匹配一体化）
# ============================================================

def scan_images(max_images=None, start_id=None, resume=False, dry_run=False):
    """Main pipeline with face registration and matching."""
    log.info("=" * 60)
    log.info("数据初始化管线启动（含人脸注册+匹配）")
    log.info(f"  VM_API: {VM_API}")
    log.info(f"  DB: {DB_USER}@{DB_HOST}:{DB_PORT}/{DB_NAME}")
    log.info(f"  Data: {DATA_ROOT}")
    log.info(f"  Max images: {max_images or 'unlimited'}")
    log.info(f"  Resume: {resume}")
    log.info(f"  Dry run: {dry_run}")
    log.info("=" * 60)

    conn = db_connect()
    cur = conn.cursor()
    ensure_seed_data(cur)
    student_map = get_student_map(cur)
    log.info("  Known students: %s", student_map)

    # Load checkpoint for resume
    cp = load_checkpoint() if resume else None
    processed_ids = set(cp.get('processed_ids', [])) if cp else set()
    registry = load_person_registry()
    log.info("  Already processed: %d images", len(processed_ids))
    log.info("  Person registry: %d persons", len(registry['persons']))

    all_images = sorted(DATA_ROOT.rglob('*.jpg'))
    if not all_images:
        log.error("No JPG images found in %s", DATA_ROOT)
        return
    log.info("  Total images found: %d", len(all_images))

    stats = {'scanned': 0, 'detected_faces': 0, 'emotions': 0,
             'matched_to_person': 0, 'registered_new': 0,
             'errors': 0, 'skipped': 0, 'start_time': time.time()}

    for idx, img_path in enumerate(all_images):
        if max_images and stats['scanned'] >= max_images:
            log.info("Reached max-images limit (%d)", max_images)
            break
        if start_id and idx < start_id:
            continue

        rel_path = str(img_path.relative_to(DATA_ROOT))
        path_key = str(img_path)

        if path_key in processed_ids:
            stats['skipped'] += 1
            continue

        # Parse path: {school}/{class}/{date}/{period}/{filename}
        parts = rel_path.replace('\\', '/').split('/')
        school = parts[0] if len(parts) >= 1 else '官渡一中'
        class_name = parts[1] if len(parts) >= 2 else '初一班'
        period_label = parts[3] if len(parts) >= 4 else 'other'
        filename = parts[-1] if parts else ''

        capture_time = parse_filename_datetime(filename)
        if capture_time is None:
            capture_time = datetime.fromtimestamp(os.path.getmtime(img_path))

        # Read image
        try:
            with open(img_path, 'rb') as f:
                image_bytes = f.read()
        except Exception as e:
            log.error("  Cannot read %s: %s", rel_path, e)
            stats['errors'] += 1
            continue

        # Step 1: Face detection
        log.info("[%d/%d] %s", idx + 1, len(all_images), rel_path)
        try:
            faces = call_detect_grpc(image_bytes)
        except Exception as e:
            log.warning("  Detect failed: %s", e)
            stats['errors'] += 1
            continue

        if not faces:
            log.info("  No faces detected")
            if not dry_run:
                insert_class_image(cur, 1, str(img_path), capture_time, period_label, 'COMPLETED')
                cur.execute("""
                    UPDATE class_image SET face_detected_count = 0, emotion_recognized_count = 0
                    WHERE image_url = %s
                """, (str(img_path),))
                conn.commit()
            stats['scanned'] += 1
            processed_ids.add(path_key)
            continue

        # Step 2: Insert class_image
        if not dry_run:
            ci_id, _ = insert_class_image(cur, 1, str(img_path), capture_time, period_label, 'COMPLETED')
        else:
            ci_id = -1

        stats['scanned'] += 1
        face_in_image = 0
        emotion_in_image = 0

        for fi, face in enumerate(faces):
            bbox_list = face.get('bbox', [0, 0, 0, 0])
            confidence = face.get('confidence', 0)
            quality = face.get('quality', 0)
            emotion_label = face.get('emotion_label')
            emotion_index = face.get('emotion_index')

            if confidence < CONFIDENCE_THRESHOLD:
                continue

            x, y, w, h = bbox_list[:4]
            bbox_json = json.dumps({'x': x, 'y': y, 'width': w, 'height': h})

            # Step 3: Crop face
            crop_bytes = crop_face(image_bytes, [x, y, w, h])

            if not dry_run and crop_bytes:
                # Step 3a: VisionMind 1:N search → match or register
                matches = vm_search_face(crop_bytes)
                person_id = None
                student_id = None

                if matches:
                    matched_id, similarity = matches[0]
                    person_id = get_or_create_person(registry, matched_id=matched_id)
                    update_person_stats(registry, person_id, similarity)
                    # 如果该 person 已关联 student，直接使用
                    person_info = registry['persons'].get(person_id, {})
                    student_id = person_info.get('student_id')
                    stats['matched_to_person'] += 1
                    log.debug("  Face matched to %s (sim=%.3f)", person_id, similarity)
                else:
                    # 新面孔：注册到人脸库
                    person_id = get_or_create_person(registry)
                    reg_ok = vm_register_face(person_id, crop_bytes,
                                              extra_json=json.dumps({'face_record_id': ci_id}))
                    stats['registered_new'] += 1
                    log.debug("  New face registered as %s", person_id)

                # Step 3b: Save cropped image to disk
                date_str = capture_time.strftime('%Y-%m-%d') if capture_time else 'unknown'
                crop_url = save_crop_to_disk(crop_bytes, school, class_name,
                                             date_str, period_label, ci_id * 1000 + fi)

                # Step 4: Insert face_record (with person linkage)
                fr_id = insert_face_record(cur, ci_id, bbox_json, confidence,
                                           quality, student_id, person_id, crop_url)
                face_in_image += 1
                stats['detected_faces'] += 1

                # Step 5: Insert emotion_record
                if emotion_label:
                    dominant_state = map_to_dominant_state(emotion_label)
                    insert_emotion_record(cur, fr_id, emotion_label, confidence,
                                          [], dominant_state)
                    stats['emotions'] += 1
                    emotion_in_image += 1

        # Commit per image
        if not dry_run:
            # Update class_image counters
            cur.execute("""
                UPDATE class_image
                SET face_detected_count = %s,
                    emotion_recognized_count = %s
                WHERE id = %s
            """, (face_in_image, emotion_in_image, ci_id))
            conn.commit()

        # Save checkpoint
        processed_ids.add(path_key)
        save_checkpoint({'processed_ids': list(processed_ids),
                         'stats': stats, 'last_file': rel_path})
        save_person_registry(registry)

        # Progress log
        if (idx + 1) % BATCH_SIZE == 0:
            elapsed = time.time() - stats['start_time']
            rate = (idx + 1) / elapsed if elapsed > 0 else 0
            log.info("--- Progress: %d/%d images, %.1f img/min, %d faces, %d emotions, %d matched ---",
                     idx + 1, len(all_images), rate * 60,
                     stats['detected_faces'], stats['emotions'], stats['matched_to_person'])

    # Final stats
    elapsed = time.time() - stats['start_time']
    log.info("=" * 60)
    log.info("完成!")
    log.info(f"  处理: {stats['scanned']} 张图片")
    log.info(f"  检测: {stats['detected_faces']} 张人脸")
    log.info(f"  情绪: {stats['emotions']} 条记录")
    log.info(f"  匹配到已有人员: {stats['matched_to_person']}")
    log.info(f"  注册为新面孔: {stats['registered_new']}")
    log.info(f"  错误: {stats['errors']}")
    log.info(f"  跳过: {stats['skipped']}")
    log.info(f"  耗时: {elapsed:.0f}s ({stats['scanned']/elapsed:.1f} img/s)")
    log.info("=" * 60)

    # Print person summary
    persons_sorted = sorted(registry['persons'].items(), key=lambda x: x[1]['face_count'], reverse=True)
    log.info("Top persons (largest clusters = most frequent students):")
    for pid, info in persons_sorted[:10]:
        sid = info.get('student_id')
        sid_str = f"→ student_{sid}" if sid else "(unmatched)"
        log.info("  %s: %d faces, avg_sim=%.3f %s", pid, info['face_count'], info['avg_similarity'], sid_str)

    cur.close()
    conn.close()


def load_checkpoint():
    try:
        with open(CHECKPOINT_FILE, 'r') as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return None

def save_checkpoint(data):
    with open(CHECKPOINT_FILE, 'w') as f:
        json.dump(data, f)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='数据初始化管线（含人脸注册+匹配）')
    parser.add_argument('--max-images', type=int, default=None, help='最大处理图片数')
    parser.add_argument('--start-id', type=int, default=None, help='起始索引')
    parser.add_argument('--resume', action='store_true', help='断点续传')
    parser.add_argument('--dry-run', action='store_true', help='干跑模式')
    parser.add_argument('--match-only', action='store_true',
                        help='仅对已有 face_record 做匹配（不重新检测）')
    args = parser.parse_args()

    if args.match_only:
        match_existing_faces(dry_run=args.dry_run)
    else:
        scan_images(max_images=args.max_images, start_id=args.start_id,
                    resume=args.resume, dry_run=args.dry_run)
