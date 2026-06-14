#!/usr/bin/env python3
"""
明眸·校园学生状态感知平台 — 数据初始化管线
从 data/ 目录读取全景课堂照片，通过 face_server gRPC 进行：
  1. 人脸检测 + 512维特征提取 (gRPC Analyze, enabled_features=0xB3)
  2. 人脸裁剪 (本地 Pillow)
  3. 情绪识别
  4. 基于特征余弦相似度的 1:N 人员匹配（阈值 0.55）
  5. 空间位置辅助聚类（同 period 内 bbox 中心距 < 200px）
  6. 保存裁剪图到 /images/cropped/
  7. 写入 PostgreSQL (class_image + face_record + emotion_record + person 关联)
  8. 更新 class_image 计数器字段

用法:
  python3 init_data_pipeline.py                    # 全量处理
  python3 init_data_pipeline.py --max-images 100   # 仅处理前100张
  python3 init_data_pipeline.py --resume           # 断点续传
  python3 init_data_pipeline.py --start-id 500     # 从指定索引开始
  python3 init_data_pipeline.py --dry-run          # 干跑
"""

import os, sys, json, time, base64, re, struct, math
from pathlib import Path
from datetime import datetime
from io import BytesIO
import argparse
import logging

import numpy as np
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

GRPC_HOST = os.environ.get('GRPC_HOST', 'localhost:50053')
DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')

DATA_ROOT = Path('/media/zebra/data/官渡一中初一班-0526/data')
CHECKPOINT_FILE = '/tmp/init_pipeline_checkpoint.json'
PERSONS_FILE = '/tmp/pipeline_persons.json'

CONFIDENCE_THRESHOLD = 0.3
CROP_MARGIN = 0.30
GRPC_TIMEOUT = 180
BATCH_SIZE = 50

# 人脸匹配阈值
FEATURE_MATCH_THRESHOLD = 0.55      # 特征余弦相似度阈值
SPATIAL_DISTANCE_THRESHOLD = 200     # 同 period bbox 中心距阈值(px)
SAME_PERIOD_MATCH_BOOST = 0.05       # 同 period 额外加分

CROP_OUTPUT_ROOT = Path('/media/zebra/data/官渡一中初一班-0526/emotion-platform/images')

# ============================================================
#  人脸匹配器（增量式维护人员库）
# ============================================================

class FaceMatcher:
    """基于512维特征向量 + 空间位置的增量式人脸匹配器。
    为每个 person 维护座位历史，在 match() 中使用空间距离辅助决策。
    """

    def __init__(self, registry_path=PERSONS_FILE):
        self.registry_path = registry_path
        self.persons = {}
        self.next_pid = 1
        self._load()

    def _load(self):
        try:
            with open(self.registry_path) as f:
                data = json.load(f)
            self.persons = data.get('persons', {})
            self.next_pid = data.get('next_pid', 1)
            # 兼容旧格式：给没有 seats 的 person 初始化空列表
            for p in self.persons.values():
                p.setdefault('seats', [])
            log.info("  Loaded %d persons from registry, next_pid=%s", len(self.persons), self.next_pid)
        except (FileNotFoundError, json.JSONDecodeError):
            pass

    def save(self):
        with open(self.registry_path, 'w') as f:
            json.dump({'persons': self.persons, 'next_pid': self.next_pid}, f, indent=2)

    def _normalize(self, vec):
        n = np.linalg.norm(vec)
        return vec / n if n > 1e-10 else vec

    def record_seat(self, person_id, bbox_center, period=None):
        """记录一个人脸的座位位置"""
        entry = self.persons.get(person_id)
        if not entry or not bbox_center:
            return
        seats = entry.setdefault('seats', [])
        seats.append([bbox_center[0], bbox_center[1], period])
        if len(seats) > 100:
            seats.pop(0)

    def _rebuild_feature_matrix(self):
        """重建归一化特征矩阵（persons 更新后调用）"""
        pids = list(self.persons.keys())
        if not pids:
            self._feature_pids = []
            self._feature_matrix = np.empty((0, 512), dtype=np.float32)
            return
        matrix = np.array([self.persons[pid]['avg_feature'] for pid in pids], dtype=np.float32)
        norms = np.linalg.norm(matrix, axis=1, keepdims=True)
        norms[norms < 1e-10] = 1
        self._feature_matrix = matrix / norms
        self._feature_pids = pids

    def match(self, feature_vec, bbox_center=None, period=None):
        """匹配人脸: 返回 (person_id, score)
        - feature_vec: 512-dim float32 numpy array
        - bbox_center: (cx, cy) 座位位置 → 用于空间加分/扣分
        - period: 当前节次标签 → 同节次内空间匹配加分更多
        """
        if feature_vec is None or len(self.persons) == 0:
            return None, 0.0

        # 重建矩阵（懒加载 + 增量更新）
        if not hasattr(self, '_feature_pids') or len(self._feature_pids) != len(self.persons):
            self._rebuild_feature_matrix()

        query_n = self._normalize(feature_vec)

        # 向量化特征匹配 (8414 × 512 dot → 8414 scores)
        sims = np.dot(self._feature_matrix, query_n)
        best_idx = int(np.argmax(sims))
        best_sim = float(sims[best_idx])
        best_pid = self._feature_pids[best_idx]

        # 空间加分/扣分（只在最佳候选中做，不遍历所有）
        if bbox_center:
            entry = self.persons.get(best_pid)
            seats = entry.get('seats', []) if entry else []
            if seats:
                min_dist = min(
                    math.hypot(bbox_center[0] - sx, bbox_center[1] - sy)
                    for sx, sy, _ in seats
                )
                if period:
                    period_dists = [
                        math.hypot(bbox_center[0] - sx, bbox_center[1] - sy)
                        for sx, sy, p in seats if p == period
                    ]
                    period_dist = min(period_dists) if period_dists else min_dist
                else:
                    period_dist = min_dist

                if min_dist < SPATIAL_DISTANCE_THRESHOLD:
                    best_sim += 0.08
                    if period_dist < 100:
                        best_sim += 0.04
                elif min_dist > SPATIAL_DISTANCE_THRESHOLD * 3:
                    best_sim -= 0.10

        if best_sim >= FEATURE_MATCH_THRESHOLD:
            return best_pid, best_sim
        return None, best_sim

    def register_new(self, feature_vec, person_id=None):
        if person_id is None:
            person_id = f'person_{self.next_pid}'
            self.next_pid += 1
        self.persons[person_id] = {
            'avg_feature': feature_vec.tolist() if isinstance(feature_vec, np.ndarray) else list(feature_vec),
            'face_count': 1,
            'student_id': None,
            'seats': [],
        }
        return person_id

    def update_person(self, person_id, feature_vec):
        entry = self.persons[person_id]
        n = entry['face_count']
        old_avg = np.array(entry['avg_feature'], dtype=np.float32)
        new_vec = np.array(feature_vec, dtype=np.float32) if not isinstance(feature_vec, np.ndarray) else feature_vec
        entry['avg_feature'] = ((old_avg * n) + new_vec / (n + 1)).tolist()
        entry['face_count'] = n + 1

# ============================================================
#  数据库
# ============================================================

def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)

def ensure_seed_data(cur):
    cur.execute("INSERT INTO grade (id, name, sort_order) VALUES (1, '初一', 1) ON CONFLICT (id) DO NOTHING")
    cur.execute("""INSERT INTO class (id, grade_id, name, sort_order)
                   VALUES (1, 1, '初一班', 1) ON CONFLICT (id) DO NOTHING""")
    cur.connection.commit()

def insert_or_get_class_image(cur, class_id, image_url, capture_time, period_label):
    cur.execute("SELECT id FROM class_image WHERE image_url = %s", (image_url,))
    row = cur.fetchone()
    if row:
        return row[0], False
    if capture_time is None:
        capture_time = datetime.now()
    cur.execute("""INSERT INTO class_image
                   (class_id, image_url, capture_time, period_label, status, source)
                   VALUES (%s, %s, %s, %s, 'COMPLETED', 'auto_scan') RETURNING id""",
                (class_id, image_url, capture_time, period_label))
    return cur.fetchone()[0], True

def insert_face_record(cur, class_image_id, bbox_json, confidence, quality,
                        cropped_image_url, face_encoding, person_id, gender=None):
    """写入 face_record，包括特征向量、person 关联和性别属性 (0=female, 1=male)"""
    cur.execute("""INSERT INTO face_record
                   (class_image_id, bbox, confidence, quality,
                    cropped_image_url, face_encoding,
                    lib_face_id, lib_register_status, status, gender)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'DETECTED', %s) RETURNING id""",
                (class_image_id, bbox_json, confidence, quality,
                 cropped_image_url, face_encoding,
                 person_id,
                 'registered' if person_id else 'pending',
                 gender))
    return cur.fetchone()[0]

def insert_emotion_record(cur, face_record_id, dominant_emotion, dominant_confidence,
                           probs_list, dominant_state=None):
    probs = probs_list if probs_list else [None] * 7
    cur.execute("""INSERT INTO emotion_record
                   (face_record_id, dominant_emotion, dominant_confidence,
                    emotion_neutral, emotion_happy, emotion_sad,
                    emotion_surprise, emotion_fear, emotion_disgust, emotion_angry,
                    dominant_state)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) RETURNING id""",
                (face_record_id, dominant_emotion, dominant_confidence,
                 probs[0], probs[1], probs[2], probs[3],
                 probs[4], probs[5], probs[6], dominant_state))
    return cur.fetchone()[0]

def update_class_image_counters(cur, ci_id, face_count, emotion_count):
    cur.execute("""UPDATE class_image
                   SET face_detected_count = %s,
                       emotion_recognized_count = %s
                   WHERE id = %s""",
                (face_count, emotion_count, ci_id))

# ============================================================
#  gRPC 引擎调用（含特征提取）
# ============================================================

_GRPC_STUB = None

def get_grpc_stub():
    global _GRPC_STUB
    if _GRPC_STUB is None:
        channel = grpc.insecure_channel(GRPC_HOST,
            options=[('grpc.max_send_message_length', 50*1024*1024),
                     ('grpc.max_receive_message_length', 50*1024*1024)])
        _GRPC_STUB = FaceServiceStub(channel)
    return _GRPC_STUB

def call_detect_grpc(image_bytes):
    """Call face_server Analyze with 0xB3 = DETECT|FEATURE|ATTRIBUTE|QUALITY|EMOTION"""
    try:
        stub = get_grpc_stub()
        # 0xB3 = 0x01(DETECT) | 0x10(ATTRIBUTE) | 0x20(QUALITY) | 0x80(EMOTION) | 0x02(FEATURE)
        req = FaceAnalysisRequest(image_data=image_bytes, enabled_features=0xB3)
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
            # 提取性别属性 (0=female, 1=male)
            if f.HasField('attribute'):
                face['gender'] = f.attribute.gender
            # 提取512维特征向量
            if f.feature_dim > 0:
                vals = struct.unpack(f'{f.feature_dim}f', f.feature)
                face['feature'] = np.array(vals, dtype=np.float32)
            faces.append(face)
        return faces
    except Exception as e:
        log.warning("  gRPC detect error: %s", e)
        return []

# ============================================================
#  图片处理
# ============================================================

def crop_face(image_bytes, bbox, margin=CROP_MARGIN):
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

def save_cropped_face(crop_bytes, school, class_name, date_str, period, face_record_id):
    period_safe = re.sub(r'[\\/:*?"<>|]', '_', period) if period else 'other'
    img_dir = CROP_OUTPUT_ROOT / 'cropped' / school / class_name / date_str / period_safe
    img_dir.mkdir(parents=True, exist_ok=True)
    output_path = img_dir / f'face_{face_record_id}.jpg'
    with open(output_path, 'wb') as f:
        f.write(crop_bytes)
    return f'/images/cropped/{school}/{class_name}/{date_str}/{period_safe}/face_{face_record_id}.jpg'

def parse_filename_datetime(filename):
    m = re.match(r'(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})', filename)
    if m:
        return datetime(int(m.group(1)), int(m.group(2)), int(m.group(3)),
                       int(m.group(4)), int(m.group(5)), int(m.group(6)))
    return None

def map_to_dominant_state(emotion_label):
    mapping = {
        '中性': 'ENGAGED', '开心': 'ENGAGED', '惊讶': 'ENGAGED',
        '伤心': 'WITHDRAWN', '恐惧': 'WITHDRAWN',
        '厌恶': 'CONFUSED', '愤怒': 'CONFUSED',
    }
    return mapping.get(emotion_label, 'UNKNOWN')

# ============================================================
#  检查点
# ============================================================

def save_checkpoint(data):
    with open(CHECKPOINT_FILE, 'w') as f:
        json.dump(data, f)

def load_checkpoint():
    try:
        with open(CHECKPOINT_FILE, 'r') as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return None

# ============================================================
#  主流程
# ============================================================

def scan_images(max_images=None, start_id=None, resume=False, dry_run=False):
    log.info("=" * 60)
    log.info("数据初始化管线启动（含特征提取+人脸匹配）")
    log.info(f"  特征匹配阈值: {FEATURE_MATCH_THRESHOLD}")
    log.info(f"  空间距离阈值: {SPATIAL_DISTANCE_THRESHOLD}px")
    log.info("=" * 60)

    conn = db_connect()
    cur = conn.cursor()
    ensure_seed_data(cur)
    matcher = FaceMatcher()
    cp = load_checkpoint() if resume else None
    processed_ids = set(cp.get('processed_ids', [])) if cp else set()

    # Resume 时从数据库加载已处理的图片和人员信息
    if resume:
        # 1. 从 class_image 表找出已处理的图片路径
        cur.execute("SELECT image_url FROM class_image WHERE status = 'COMPLETED'")
        db_processed = {row[0] for row in cur.fetchall()}
        log.info("  DB 中已处理的 class_image: %d 张", len(db_processed))
        # 将 DB 中已存在的也纳入 processed_ids，避免重复创建
        processed_ids.update(db_processed)

        # 2. 恢复 person 注册表：从 face_encoding 重建平均特征向量
        #    按 lib_face_id 分组，聚合特征向量
        cur.execute("""
            SELECT fr.lib_face_id, 
                   MIN(fr.face_encoding) as face_encoding, 
                   COUNT(*) as cnt
            FROM face_record fr
            WHERE fr.lib_face_id IS NOT NULL
              AND fr.lib_face_id LIKE 'person_%%'
              AND fr.face_encoding IS NOT NULL
              AND fr.face_encoding != ''
            GROUP BY fr.lib_face_id
        """)
        db_persons = cur.fetchall()
        loaded_from_db = 0
        for pid, enc_b64, cnt in db_persons:
            try:
                raw = base64.b64decode(enc_b64)
                vec = np.frombuffer(raw, dtype=np.float32)
                if len(vec) != 512:
                    continue
                # 合并：如果 registry 已有则取平均，否则用 DB 的
                if pid in matcher.persons:
                    old_cnt = matcher.persons[pid]['face_count']
                    old_vec = np.array(matcher.persons[pid]['avg_feature'], dtype=np.float32)
                    merged = (old_vec * old_cnt + vec) / (old_cnt + 1)
                    matcher.persons[pid]['avg_feature'] = merged.tolist()
                    matcher.persons[pid]['face_count'] = old_cnt + 1
                else:
                    matcher.persons[pid] = {
                        'avg_feature': vec.tolist(),
                        'face_count': cnt,
                        'student_id': None,
                    }
                loaded_from_db += 1
            except Exception as e:
                log.warning("  跳过 person %s: %s", pid, e)

            if pid and pid.startswith('person_'):
                try:
                    num = int(pid.split('_')[1])
                    if num >= matcher.next_pid:
                        matcher.next_pid = num + 1
                except ValueError:
                    pass

        log.info("  FaceMatcher 恢复: %d persons (from DB), next_pid=%s",
                 loaded_from_db, matcher.next_pid)
        cur.execute("""
            SELECT fr.lib_face_id, fr.student_id
            FROM face_record fr
            WHERE fr.lib_face_id IS NOT NULL AND fr.student_id IS NOT NULL
        """)
        for pid, sid in cur.fetchall():
            if pid in matcher.persons:
                matcher.persons[pid]['student_id'] = sid

        # 3. 恢复座位历史：从 bbox 字段重建每个人的座位位置
        cur.execute("""
            SELECT fr.lib_face_id,
                   ((fr.bbox::json->>'x')::numeric + (fr.bbox::json->>'width')::numeric / 2)::int AS cx,
                   ((fr.bbox::json->>'y')::numeric + (fr.bbox::json->>'height')::numeric / 2)::int AS cy,
                   ci.period_label
            FROM face_record fr
            JOIN class_image ci ON fr.class_image_id = ci.id
            WHERE fr.lib_face_id IS NOT NULL
              AND fr.lib_face_id LIKE 'person_%%'
              AND fr.bbox IS NOT NULL AND fr.bbox != ''
        """)
        seat_count = 0
        for pid, cx, cy, period in cur.fetchall():
            if pid in matcher.persons:
                matcher.persons[pid].setdefault('seats', []).append([cx, cy, period])
                seat_count += 1
        log.info("  座位历史恢复: %d 条 (seats)", seat_count)

    all_images = sorted(DATA_ROOT.rglob('*.jpg'))
    log.info(f"  Total images: {len(all_images)}, already processed: {len(processed_ids)}")

    stats = {'scanned': 0, 'detected_faces': 0, 'emotions': 0,
             'matched': 0, 'new_persons': 0,
             'errors': 0, 'skipped': 0, 'start_time': time.time()}

    for idx, img_path in enumerate(all_images):
        if max_images and stats['scanned'] >= max_images:
            break
        if start_id and idx < start_id:
            continue

        rel_path = str(img_path.relative_to(DATA_ROOT))
        path_key = str(img_path)
        if path_key in processed_ids:
            stats['skipped'] += 1
            continue

        parts = rel_path.replace('\\', '/').split('/')
        school = parts[0] if len(parts) >= 1 else '官渡一中'
        class_name = parts[1] if len(parts) >= 2 else '初一班'
        period_label = parts[3] if len(parts) >= 4 else 'other'
        filename = parts[-1] if parts else ''

        capture_time = parse_filename_datetime(filename)
        if capture_time is None:
            capture_time = datetime.fromtimestamp(os.path.getmtime(img_path))

        try:
            with open(img_path, 'rb') as f:
                image_bytes = f.read()
        except Exception as e:
            log.error("  Cannot read %s: %s", rel_path, e)
            stats['errors'] += 1
            continue

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
                ci_id, _ = insert_or_get_class_image(cur, 1, str(img_path), capture_time, period_label)
                update_class_image_counters(cur, ci_id, 0, 0)
                conn.commit()
            stats['scanned'] += 1
            processed_ids.add(path_key)
            continue

        if not dry_run:
            ci_id, _ = insert_or_get_class_image(cur, 1, str(img_path), capture_time, period_label)
        else:
            ci_id = -1

        stats['scanned'] += 1
        face_count = 0
        emotion_count = 0
        date_str = capture_time.strftime('%Y-%m-%d') if capture_time else 'unknown'

        for fi, face in enumerate(faces):
            bbox_list = face.get('bbox', [0, 0, 0, 0])
            confidence = face.get('confidence', 0)
            quality = face.get('quality', 0)
            emotion_label = face.get('emotion_label')
            gender = face.get('gender')  # 0=female, 1=male (from gRPC FaceAttribute)
            feature_vec = face.get('feature')
            has_feature = feature_vec is not None

            if confidence < CONFIDENCE_THRESHOLD:
                continue

            x, y, w, h = bbox_list[:4]
            bbox_json = json.dumps({'x': x, 'y': y, 'width': w, 'height': h})
            bbox_center = (x + w // 2, y + h // 2) if w > 0 and h > 0 else None

            crop_bytes = crop_face(image_bytes, [x, y, w, h])

            if not dry_run:
                # 人脸匹配（特征 + 空间位置联合评分）
                person_id = None
                if has_feature:
                    pid, sim = matcher.match(feature_vec, bbox_center, period=period_label)
                    if pid:
                        person_id = pid
                        matcher.update_person(pid, feature_vec)
                        matcher.record_seat(pid, bbox_center, period_label)
                        stats['matched'] += 1
                    else:
                        person_id = matcher.register_new(feature_vec)
                        matcher.record_seat(person_id, bbox_center, period_label)
                        stats['new_persons'] += 1

                # 编码特征向量为 base64 存储
                face_encoding_b64 = None
                if has_feature:
                    face_encoding_b64 = base64.b64encode(feature_vec.tobytes()).decode()

                # 写入 face_record
                fr_id = insert_face_record(cur, ci_id, bbox_json, confidence, quality,
                                            cropped_image_url=None,
                                            face_encoding=face_encoding_b64,
                                            person_id=person_id,
                                            gender=gender)
                face_count += 1
                stats['detected_faces'] += 1

                # 保存裁剪图
                if crop_bytes:
                    crop_url = save_cropped_face(crop_bytes, school, class_name,
                                                 date_str, period_label, fr_id)
                    cur.execute("UPDATE face_record SET cropped_image_url = %s WHERE id = %s",
                               (crop_url, fr_id))

                # 情绪记录
                if emotion_label:
                    dominant_state = map_to_dominant_state(emotion_label)
                    insert_emotion_record(cur, fr_id, emotion_label, confidence,
                                          [], dominant_state)
                    emotion_count += 1
                    stats['emotions'] += 1

        # 提交
        if not dry_run:
            update_class_image_counters(cur, ci_id, face_count, emotion_count)
            conn.commit()

        processed_ids.add(path_key)

        if (idx + 1) % BATCH_SIZE == 0:
            # 每批提交后保存 checkpoint 和 person registry（各约 11s，不能每张图都做）
            save_checkpoint({'processed_ids': list(processed_ids),
                             'stats': stats, 'last_file': rel_path})
            matcher.save()
            elapsed = time.time() - stats['start_time']
            rate = (idx + 1) / elapsed if elapsed > 0 else 0
            log.info("--- Progress: %d/%d images, %.1f img/min, %d faces, %d matched/%d new ---",
                     idx + 1, len(all_images), rate * 60,
                     stats['detected_faces'], stats['matched'], stats['new_persons'])

    # 最终报告
    elapsed = time.time() - stats['start_time']
    matcher.save()
    log.info("=" * 60)
    log.info("完成!")
    log.info(f"  处理: {stats['scanned']} 张图片")
    log.info(f"  人脸: {stats['detected_faces']} 张 ({stats['matched']}匹配 + {stats['new_persons']}新)")
    log.info(f"  情绪: {stats['emotions']} 条记录")
    log.info(f"  错误: {stats['errors']}, 跳过: {stats['skipped']}")
    log.info(f"  耗时: {elapsed:.0f}s")
    log.info(f"  Person注册表: {len(matcher.persons)} 人")
    log.info("=" * 60)

    # 输出各 person 统计
    sorted_p = sorted(matcher.persons.items(), key=lambda x: x[1]['face_count'], reverse=True)
    log.info("Top persons:")
    for pid, info in sorted_p[:10]:
        sid = info.get('student_id')
        sid_str = f"→ student_{sid}" if sid else ""
        log.info("  %s: %d faces%s", pid, info['face_count'], sid_str)

    cur.close()
    conn.close()


# ============================================================
#  Person → Student 关联（基于频率的自动标注）
# ============================================================

def link_students(dry_run=False, min_frequency=3):
    """将管线聚类出的高频 person 自动关联到学生。
    
    策略：
    1. 已有 student 表 → 按 face_count 分配 top N 个 person
    2. 剩余 face_count >= min_frequency 的 person → 自动创建新学生
    3. 低频 person (face_count < min_frequency) 保持未关联
    """
    conn = db_connect()
    cur = conn.cursor()

    # 获取已有学生
    cur.execute("SELECT id, name, student_no FROM student ORDER BY id")
    existing_students = cur.fetchall()
    log.info("已有学生: %d 人", len(existing_students))

    # 获取当前最大学号
    cur.execute("SELECT COALESCE(MAX(CAST(SUBSTRING(student_no FROM 4) AS INTEGER)), 0) FROM student")
    max_no = cur.fetchone()[0] or 0

    # 获取所有已聚类但未关联的 person（按出现频率降序）
    cur.execute("""
        SELECT lib_face_id, count(*) as face_count
        FROM face_record
        WHERE lib_face_id IS NOT NULL
          AND (student_id IS NULL OR student_id = 0)
        GROUP BY lib_face_id
        ORDER BY count(*) DESC
    """)
    unlinked_persons = cur.fetchall()
    log.info("未关联 person: %d 个 (face_count >= %d: %d 个)",
             len(unlinked_persons), min_frequency,
             sum(1 for _, c in unlinked_persons if c >= min_frequency))

    if not unlinked_persons:
        log.info("所有 person 已关联")
        cur.close()
        conn.close()
        return

    n_existing = len(existing_students)
    linked = 0
    new_students = 0

    log.info("=" * 60)
    log.info("学生关联流程")
    log.info("-" * 60)

    # 阶段1: 已有学生 → 分配 top N 个高频 person
    for i, (student_id, student_name, _) in enumerate(existing_students):
        if i >= len(unlinked_persons):
            break
        person_id, face_count = unlinked_persons[i]
        if not dry_run:
            cur.execute("""UPDATE face_record SET student_id = %s
                           WHERE lib_face_id = %s AND (student_id IS NULL OR student_id = 0)""",
                        (student_id, person_id))
            cnt = cur.rowcount
            conn.commit()
        else:
            cnt = '(dry-run)'
        log.info("  [已有] %s (%d faces) → %s (student_id=%s): %s",
                 person_id, face_count, student_name, student_id, cnt)
        linked += 1

    # 阶段2: 剩余高频 person → 创建新学生
    for person_id, face_count in unlinked_persons[n_existing:]:
        if face_count < min_frequency:
            break
        max_no += 1
        new_id = max_no
        student_no = f'stu{new_id:04d}'
        student_name = f'学生{new_id:03d}'

        if not dry_run:
            cur.execute("""INSERT INTO student (id, name, student_no, status, class_id)
                           VALUES (DEFAULT, %s, %s, 'active', 1) RETURNING id""",
                        (student_name, student_no))
            new_student_id = cur.fetchone()[0]
            cur.execute("SELECT setval('student_id_seq', GREATEST(nextval('student_id_seq'), %s))",
                       (new_student_id,))
            cur.execute("""UPDATE face_record SET student_id = %s
                           WHERE lib_face_id = %s AND (student_id IS NULL OR student_id = 0)""",
                        (new_student_id, person_id))
            cnt = cur.rowcount
            conn.commit()
        else:
            new_student_id = '(dry-run)'
            cnt = '(dry-run)'

        log.info("  [新建] %s (%d faces) → %s (student_id=%s): %s",
                 person_id, face_count, student_name, new_student_id, cnt)
        linked += 1
        new_students += 1

    # 更新 person registry
    try:
        with open(PERSONS_FILE) as f:
            registry = json.load(f)
        persons_dict = registry.get('persons', {})

        cur.execute("SELECT id, name FROM student WHERE id NOT IN (SELECT id FROM student LIMIT 0)")
        cur.execute("SELECT id, name FROM student ORDER BY id")
        all_students = cur.fetchall()

        for person_id, face_count in unlinked_persons:
            if face_count < min_frequency:
                break
            if person_id in persons_dict:
                cur.execute("SELECT student_id FROM face_record WHERE lib_face_id = %s AND student_id IS NOT NULL LIMIT 1", (person_id,))
                row = cur.fetchone()
                if row:
                    persons_dict[person_id]['student_id'] = row[0]

        registry['persons'] = persons_dict
        with open(PERSONS_FILE, 'w') as f:
            json.dump(registry, f, indent=2)
    except Exception as e:
        log.warning("更新 person registry 失败: %s", e)

    log.info("-" * 60)
    log.info("关联完成: %d 个学生 (%d 新建)", linked, new_students)

    cur.execute("SELECT count(*) FROM face_record WHERE student_id IS NOT NULL")
    linked_total = cur.fetchone()[0]
    log.info("face_record.student_id 非空: %s 条 / %s 总", linked_total,
             (cur.execute("SELECT count(*) FROM face_record") or cur.fetchone() or ['?'])[0])

    cur.close()
    conn.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='数据初始化管线（含特征提取+人脸匹配）')
    parser.add_argument('--max-images', type=int, default=None)
    parser.add_argument('--start-id', type=int, default=None)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--link-students', action='store_true',
                        help='自动关联 top person 到已知学生')
    args = parser.parse_args()
    if args.link_students:
        link_students(dry_run=args.dry_run)
    else:
        scan_images(max_images=args.max_images, start_id=args.start_id,
                    resume=args.resume, dry_run=args.dry_run)
