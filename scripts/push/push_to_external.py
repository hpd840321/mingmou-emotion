#!/usr/bin/env python3
"""
外部推送脚本：将本地情绪数据推送到 ylcs.htface.cn 外部平台
从本地 PostgreSQL 读取学生 + 情绪数据，调用外部 API 的 updateStudent 和 AddEmotion 接口。
图片以 base64 内嵌在 JSON payload 中。

依赖:
  pip install psycopg2-binary requests Pillow

Usage:
  python3 push_to_external.py                          # 全量推送
  python3 push_to_external.py --students-only           # 仅推送学生信息
  python3 push_to_external.py --emotions-only           # 仅推送情绪记录
  python3 push_to_external.py --dry-run                 # 干跑，不实际发送
  python3 push_to_external.py --resume                  # 断点续传
  python3 push_to_external.py --top 5                   # 只推送人脸数最多的 5 个学生
  python3 push_to_external.py --start-id 50000          # 从指定 emotion_record ID 开始

环境变量:
  DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD  — 默认 localhost:5432 / emotion / emotion
  EXTERNAL_API_BASE, PAGE_ID, CAMERA_CODE           — 外部 API 配置
"""

import os, sys, json, time, base64, logging, argparse
from datetime import datetime
from pathlib import Path

import requests
import psycopg2
import psycopg2.extras

# ---------------------------------------------------------------------------
# 日志
# ---------------------------------------------------------------------------
logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

RESPONSE_LOG_FILE = '/tmp/external_push_responses.log'
_response_log = logging.getLogger('push_responses')
_response_log.setLevel(logging.INFO)
if not _response_log.handlers:
    _rlh = logging.FileHandler(RESPONSE_LOG_FILE)
    _rlh.setFormatter(logging.Formatter('%(asctime)s %(message)s'))
    _response_log.addHandler(_rlh)

# ---------------------------------------------------------------------------
# 配置（优先读环境变量，否则用默认值）
# ---------------------------------------------------------------------------
DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASSWORD = os.environ.get('DB_PASSWORD', 'emotion')

API_BASE = os.environ.get('EXTERNAL_API_BASE', 'http://ylcs.htface.cn:33895/api/Page/Execute')
PAGE_ID = os.environ.get('PAGE_ID', 'Emotion')
CAMERA_CODE = os.environ.get('CAMERA_CODE', 'CAM_DEFAULT')

# 项目根目录 — cropped_image_url 存储的是相对于此目录的路径
PROJECT_ROOT = os.environ.get('PROJECT_ROOT', '/media/zebra/data/官渡一中初一班-0526')

CHECKPOINT_FILE = '/tmp/external_push_checkpoint.json'
AUDIT_CHECKPOINT_FILE = '/tmp/external_push_audit_checkpoint.json'
BASE64_DUMP_DIR = '/tmp/external_push_base64'

# 情绪映射
EMOTION_CN_TO_EN = {
    '中性': 'neutral', '开心': 'happy', '伤心': 'sad', '生气': 'angry',
    '惊讶': 'surprise', '恐惧': 'fear', '厌恶': 'disgust', '蔑视': 'contempt',
}
EMOTION_TO_EXTERNAL = {
    'happy': 'happy', 'sad': 'sad', 'angry': 'angry',
    'disgust': 'angry', 'surprise': 'surprised', 'fear': 'fearful',
    'neutral': 'calm', 'contempt': 'calm',
    'anxious': 'anxious',
}
EMOTION_COLORS = {
    'happy': 'green', 'sad': 'blue', 'angry': 'red',
    'calm': 'cyan', 'surprised': 'yellow', 'fearful': 'purple',
    'anxious': 'orange',
}


# ---------------------------------------------------------------------------
# 数据库连接
# ---------------------------------------------------------------------------

def db_connect():
    """连接本地 PostgreSQL"""
    return psycopg2.connect(
        host=DB_HOST, port=DB_PORT, dbname=DB_NAME,
        user=DB_USER, password=DB_PASSWORD,
    )


# ---------------------------------------------------------------------------
# 断点续传
# ---------------------------------------------------------------------------

def load_checkpoint(audit_mode=False):
    cp_file = AUDIT_CHECKPOINT_FILE if audit_mode else CHECKPOINT_FILE
    if os.path.exists(cp_file):
        with open(cp_file) as f:
            d = json.load(f)
        return (
            set(d.get('pushed_emotion_ids', [])),
            set(d.get('pushed_student_ids', [])),
            set(d.get('failed_emotion_ids', [])),
            d,
        )
    return set(), set(), set(), {
        'pushed_emotion_ids': [], 'pushed_student_ids': [], 'failed_emotion_ids': [],
        'confirmations': [],
        'stats': {'students': 0, 'emotions': 0, 'errors': 0},
    }


def save_checkpoint(emotion_ids, student_ids, failed_ids, confirmations, stats, audit_mode=False):
    cp_file = AUDIT_CHECKPOINT_FILE if audit_mode else CHECKPOINT_FILE
    data = {
        'pushed_emotion_ids': sorted(set(emotion_ids)),
        'pushed_student_ids': sorted(set(student_ids)),
        'failed_emotion_ids': sorted(set(failed_ids)),
        'confirmations': confirmations[-100:],
        'stats': stats,
    }
    tmp = cp_file + '.tmp'
    with open(tmp, 'w') as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, cp_file)


# ---------------------------------------------------------------------------
# 图片处理
# ---------------------------------------------------------------------------

def resolve_path(db_path):
    """
    解析数据库中存储的路径到真实文件系统路径。
    - cropped_image_url 存储为 /images/cropped/...（相对 PROJECT_ROOT/emotion-platform）
    - image_url 存储为 /media/zebra/data/...（已经是绝对路径）
    """
    if not db_path:
        return ''
    p = db_path.strip()
    # cropped_image_url: /images/cropped/官渡一中/...
    if p.startswith('/images/'):
        return str(Path(PROJECT_ROOT) / 'emotion-platform' / p.lstrip('/'))
    # 已经是绝对路径或其它相对路径
    if p.startswith('/'):
        return p
    return str(Path(PROJECT_ROOT) / p)


def image_file_to_base64(file_path, max_size=5 * 1024 * 1024, max_dim=640):
    if not file_path:
        return ''
    resolved = resolve_path(file_path)
    p = Path(resolved)
    if not p.exists():
        log.warning('  Image not found: %s', file_path)
        return ''
    try:
        data = p.read_bytes()
    except Exception as e:
        log.warning('  Failed to read %s: %s', file_path, e)
        return ''

    need_resize = max_dim > 0
    if need_resize:
        try:
            from PIL import Image
            import io
            img = Image.open(io.BytesIO(data))
            w, h = img.size
            if max(w, h) > max_dim:
                ratio = max_dim / max(w, h)
                new_size = (int(w * ratio), int(h * ratio))
                img = img.resize(new_size, Image.LANCZOS)
                buf = io.BytesIO()
                img.save(buf, format='JPEG', quality=85)
                data = buf.getvalue()
                log.info('  Resized %s: %dx%d -> %dx%d (%d KB)',
                         p.name, w, h, new_size[0], new_size[1], len(data) // 1024)
        except ImportError:
            log.warning('  PIL not available, cannot resize %s', file_path)
        except Exception as e:
            log.warning('  Resize failed for %s: %s', file_path, e)

    if len(data) > max_size:
        log.warning('  Image too large (%d bytes), skip: %s', len(data), file_path)
        return ''

    return base64.b64encode(data).decode('ascii')


def validate_base64_image(b64_str):
    """用魔数快速验证 base64 是否为有效 JPEG/PNG/WebP（不解码完整数据）"""
    if not b64_str:
        return False
    try:
        # 只解码前 12 字节做魔数检查
        raw = base64.b64decode(b64_str[:16])  # 16 base64 chars ≈ 12 bytes
    except Exception:
        return False
    return (
        raw[:3] == b'\xff\xd8\xff'
        or raw[:4] == b'\x89PNG'
        or (raw[:4] == b'RIFF' and len(raw) >= 12 and raw[8:12] == b'WEBP')
    )


def base64_preview(b64_str, max_front=80, max_back=20):
    if not b64_str:
        return '(empty)'
    if len(b64_str) <= max_front + max_back + 5:
        return b64_str
    return '%s...%s [%d bytes]' % (b64_str[:max_front], b64_str[-max_back:], len(b64_str))


def dump_base64(b64_str, prefix, index):
    """将 base64 写入文件供人工审核"""
    if not b64_str:
        return ''
    d = Path(BASE64_DUMP_DIR) / datetime.now().strftime('%Y%m%d')
    d.mkdir(parents=True, exist_ok=True)
    f = d / ('%s_%d.txt' % (prefix, index))
    f.write_text(b64_str)
    return str(f)


# ---------------------------------------------------------------------------
# 时间戳回退：从图片文件路径推导日期时间
# ---------------------------------------------------------------------------

import re as _re

# 目录名中的日期格式：YYYY-MM-DD 或 YYYY-MMDD（如 2026-0521）
_RE_DATE_ISO = _re.compile(r'(\d{4})-(\d{2})-(\d{2})$')
_RE_DATE_COMPRESSED = _re.compile(r'(\d{4})-(\d{4})$')


def _parse_date_dir(dirname):
    """从目录名解析日期，支持 YYYY-MM-DD 和 YYYY-MMDD 两种格式。"""
    m = _RE_DATE_ISO.match(dirname)
    if m:
        return datetime(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    m = _RE_DATE_COMPRESSED.match(dirname)
    if m:
        return datetime(int(m.group(1)), int(m.group(2)[:2]), int(m.group(2)[2:]))
    return None


def extract_datetime_from_path(file_path, prefer_mtime=True):
    """
    从图片路径中提取日期时间（当 DB 值为 NULL 时的回退方案）。
    优先用文件修改时间，同时用目录名中的日期做交叉验证。
    返回格式化的 'YYYY-MM-DD HH:MM:SS' 字符串，或 None。
    """
    resolved = resolve_path(file_path) if file_path else ''
    if not resolved:
        return None
    p = Path(resolved)
    if not p.exists():
        return None

    # 从目录结构提取日期
    date_from_path = None
    parent = p.parent
    if parent:
        date_from_path = _parse_date_dir(parent.name)
    # 如果本级目录不是日期，向上找一级
    if not date_from_path and parent and parent.parent:
        date_from_path = _parse_date_dir(parent.parent.name)

    # 文件修改时间
    mtime_dt = None
    if prefer_mtime:
        try:
            mtime_ts = p.stat().st_mtime
            mtime_dt = datetime.fromtimestamp(mtime_ts)
        except Exception:
            pass

    if date_from_path and mtime_dt:
        # 用目录中的日期 + 文件 mtime 的时间部分
        result = mtime_dt.replace(
            year=date_from_path.year,
            month=date_from_path.month,
            day=date_from_path.day,
        )
    elif mtime_dt:
        result = mtime_dt
    elif date_from_path:
        result = datetime(date_from_path.year, date_from_path.month, date_from_path.day, 12, 0, 0)
    else:
        return None

    return result.strftime('%Y-%m-%d %H:%M:%S')


# ---------------------------------------------------------------------------
# 情绪映射
# ---------------------------------------------------------------------------

def map_emotion(db_label):
    if not db_label:
        return 'calm'
    en = EMOTION_CN_TO_EN.get(db_label, db_label)
    return EMOTION_TO_EXTERNAL.get(en, 'calm')


def map_color(emotion_code):
    return EMOTION_COLORS.get(emotion_code, '')


# ---------------------------------------------------------------------------
# 审核写入：本地审计表
# ---------------------------------------------------------------------------

def audit_write_student(audit_conn, sid, sno, name, image_b64s, payload_bytes):
    with audit_conn.cursor() as cur:
        cur.execute("""
            INSERT INTO push_audit_students
                (student_id, student_no, student_name, image_count, payload_size_bytes)
            VALUES (%s, %s, %s, %s, %s)
        """, (sid, sno, name, len(image_b64s), payload_bytes))
    audit_conn.commit()


def audit_write_emotion_batch(audit_conn, batch_id, batch_records, batch_start, batch_end, payload_bytes):
    with audit_conn.cursor() as cur:
        for rec in batch_records:
            cur.execute("""
                INSERT INTO push_audit_emotions
                    (emotion_record_id, student_no, capture_time, created_at_value,
                     emotion, confidence, score, color,
                     gaze_direction, payload_size_bytes, batch_id)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (
                rec.get('Id'), rec.get('student_code'), rec.get('CaptureTime'), rec.get('created_at'),
                rec.get('Emotion'),
                float(rec.get('Confidence')) if rec.get('Confidence') else None,
                rec.get('score'), rec.get('color'),
                rec.get('GazeDirection', ''), payload_bytes, batch_id,
            ))

        cur.execute("""
            INSERT INTO push_audit_batches
                (batch_start_id, batch_end_id, record_count, payload_size_bytes)
            VALUES (%s, %s, %s, %s)
        """, (batch_start, batch_end, len(batch_records), payload_bytes))
    audit_conn.commit()


# ---------------------------------------------------------------------------
# HTTP 调用
# ---------------------------------------------------------------------------

def call_api(payload, dry_run=False):
    if dry_run:
        log.info('[DRY] Would POST: %s', json.dumps(
            {k: v for k, v in payload.items() if k not in ('ImageUrl', 'emotions')},
            ensure_ascii=False)[:500])
        return {'success': True}

    method = payload.get('method', '?')
    student_code = payload.get('student_code') or payload.get('studentCode', '')

    # base64 预览
    emotions = payload.get('emotions', [])
    if emotions:
        n = len(emotions)
        sp = ', '.join(base64_preview(e.get('SmallPic', '')) for e in emotions[:2])
        ip = ', '.join(base64_preview(e.get('ImageUrl', '')) for e in emotions[:2])
        b64_previews = '%d recs, SmallPic=[%s], ImageUrl=[%s]' % (n, sp + ('...' if n > 2 else ''), ip + ('...' if n > 2 else ''))
    else:
        imgs = payload.get('ImageUrl') or payload.get('SmallPic') or ''
        if isinstance(imgs, list):
            b64_previews = ', '.join(base64_preview(b) for b in imgs[:3]) + ('...' if len(imgs) > 3 else '')
        else:
            b64_previews = base64_preview(imgs)

    log.info('  => POST %s %s (payload ~%d bytes, images: %s)',
             method, student_code or '', len(json.dumps(payload, ensure_ascii=False)), b64_previews)

    for attempt in range(3):
        try:
            r = requests.post(API_BASE, json=payload, timeout=120)
            resp = r.json()
            if resp.get('success'):
                log.info('  <= %s OK: %s', method, json.dumps(resp, ensure_ascii=False)[:300])
                return resp
            log.warning('  <= %s attempt %d FAILED: status=%s, body=%s',
                        method, attempt + 1, r.status_code, r.text[:500])
            if attempt < 2:
                time.sleep(3)
        except Exception as e:
            log.warning('  <= %s attempt %d ERROR: %s', method, attempt + 1, str(e)[:200])
            if attempt < 2:
                time.sleep(5)
    return {'success': False, 'error': 'max retries exceeded'}


# ---------------------------------------------------------------------------
# 学生推送
# ---------------------------------------------------------------------------

def push_students(cursor, dry_run, resume, stats, pushed_student_ids, top=None, audit_conn=None):
    """
    推送学生信息：学生编号、姓名、以及最多 5 张裁剪人脸图（base64）。
    top=N 时只推送人脸数最多的 N 个学生。
    audit_conn: 不为 None 时写入审核表代替外部推送。
    """
    if top:
        cursor.execute("""
            SELECT s.id, s.student_no, s.name, COUNT(fr.id) AS face_cnt
            FROM student s
            JOIN face_record fr ON fr.student_id = s.id
            GROUP BY s.id
            ORDER BY face_cnt DESC
            LIMIT %s
        """, (top,))
    else:
        cursor.execute("""
            SELECT s.id, s.student_no, s.name, COALESCE(cnt, 0) AS face_cnt
            FROM student s
            LEFT JOIN (SELECT student_id, count(*) AS cnt FROM face_record GROUP BY student_id) fr
              ON fr.student_id = s.id
            ORDER BY s.id
        """)
    students = cursor.fetchall()
    log.info('Pushing %d students (top=%s)...', len(students), top or 'all')

    if resume and pushed_student_ids:
        students = [s for s in students if s['id'] not in pushed_student_ids]

    pushed = 0
    errors = 0
    for s in students:
        sid = s['id']
        sno = s['student_no']
        name = s['name']

        # 取该学生最新的 5 张裁剪图（按 id 降序）
        cursor.execute(
            "SELECT cropped_image_url FROM face_record WHERE student_id = %s AND cropped_image_url IS NOT NULL ORDER BY id DESC LIMIT 5",
            (sid,))
        rows = cursor.fetchall()

        image_b64s = []
        for r in rows:
            b64 = image_file_to_base64(r['cropped_image_url'])
            if b64 and validate_base64_image(b64):
                image_b64s.append(b64)
            else:
                log.warning('  Student #%d %s: invalid/skip image %s', sid, sno, r['cropped_image_url'])

        if not image_b64s:
            log.warning('  Student #%d %s: SKIP - no valid images', sid, sno)
            continue

        for i, b64 in enumerate(image_b64s):
            dp = dump_base64(b64, sno, i)
            log.info('  Student #%d %s: image[%d] %s -> %s', sid, sno, i, base64_preview(b64), dp)

        payload = {
            'pageID': PAGE_ID,
            'method': 'updateStudent',
            'student_code': sno,
            'student_name': name,
            'ImageUrl': image_b64s,
        }
        payload_bytes = len(json.dumps(payload, ensure_ascii=False))

        if audit_conn:
            audit_write_student(audit_conn, sid, sno, name, image_b64s, payload_bytes)
            pushed += 1
            log.info('  Student #%d %s: AUDITED (%d images, %d bytes)',
                     sid, sno, len(image_b64s), payload_bytes)
            continue

        resp = call_api(payload, dry_run)
        if resp.get('success'):
            pushed += 1
            pushed_student_ids.add(sid)
            log.info('  Student #%d %s: OK (%d images)', sid, sno, len(image_b64s))
        else:
            errors += 1
            log.warning('  Student #%d %s: FAIL - %s', sid, sno, resp.get('error', 'unknown'))
        _response_log.info('[updateStudent %s] %s', sno, json.dumps(resp, ensure_ascii=False)[:500])

    stats['students'] = stats.get('students', 0) + pushed
    stats['errors'] = stats.get('errors', 0) + errors
    log.info('Students pushed: %d, errors: %d', pushed, errors)
    return pushed_student_ids


# ---------------------------------------------------------------------------
# 情绪推送
# ---------------------------------------------------------------------------

def push_emotions(cursor, dry_run, resume, stats,
                  pushed_emotion_ids, failed_emotion_ids,
                  start_id=None, max_count=None, top=None, batch_size=10, audit_conn=None):
    """
    推送情绪记录（含裁剪人脸 SmallPic + 原图缩略图 ImageUrl，均为 base64）。
    audit_conn: 不为 None 时写入审核表代替外部推送。
    """
    query = """
        SELECT er.id,
               er.face_record_id,
               er.dominant_emotion,
               er.dominant_confidence,
               er.created_at,
               fr.cropped_image_url,
               fr.student_id,
               s.student_no,
               ci.capture_time,
               ci.image_url
        FROM emotion_record er
        JOIN face_record fr ON fr.id = er.face_record_id
        LEFT JOIN student s ON s.id = fr.student_id
        JOIN class_image ci ON ci.id = fr.class_image_id
        WHERE s.student_no IS NOT NULL
    """
    params = []

    if top:
        cursor.execute("""
            SELECT s2.id
            FROM student s2
            JOIN face_record fr2 ON fr2.student_id = s2.id
            GROUP BY s2.id
            ORDER BY COUNT(fr2.id) DESC
            LIMIT %s
        """, (top,))
        top_ids = [r['id'] for r in cursor.fetchall()]
        if top_ids:
            placeholders = ','.join(['%s'] * len(top_ids))
            query += ' AND fr.student_id IN (%s)' % placeholders
            params.extend(top_ids)

    if start_id:
        query += ' AND er.id >= %s'
        params.append(start_id)

    query += ' ORDER BY er.id ASC'
    if max_count:
        query += ' LIMIT %s'
        params.append(max_count)

    try:
        cursor.execute(query, params)
        records = cursor.fetchall()
    except Exception as e:
        log.error('Emotion query failed: %s', e)
        raise

    log.info('Pushing %d emotion records (batch_size=%d)...', len(records), batch_size)

    if resume and pushed_emotion_ids:
        records = [r for r in records if r['id'] not in pushed_emotion_ids]

    confirmations = []
    batch = []
    pushed = 0
    errors = 0
    total = len(records)

    for idx, rec in enumerate(records):
        er_id = rec['id']
        emotion_code = map_emotion(rec['dominant_emotion'])

        ct = rec['capture_time']
        if ct:
            ct_str = ct.strftime('%Y-%m-%d %H:%M:%S')
        else:
            ct_str = extract_datetime_from_path(rec['image_url']) or ''
        ca = rec['created_at']
        if ca:
            ca_str = ca.strftime('%Y-%m-%d %H:%M:%S')
        else:
            ca_str = extract_datetime_from_path(rec['image_url']) or ''

        show_detail = idx < 3

        # SmallPic: 裁剪人脸 base64（必填）
        small_pic_b64 = image_file_to_base64(rec['cropped_image_url'], max_size=5*1024*1024, max_dim=0)
        # ImageUrl: 原图缩放至 640px base64（必填）
        orig_img_b64 = image_file_to_base64(rec['image_url'], max_size=5*1024*1024, max_dim=640)

        valid_small = bool(small_pic_b64) and validate_base64_image(small_pic_b64)
        valid_orig = bool(orig_img_b64) and validate_base64_image(orig_img_b64)

        if not valid_small:
            log.warning('  Emotion #%d: SKIP - invalid/missing cropped image', er_id)
            failed_emotion_ids.add(er_id)
            continue

        if not valid_orig:
            log.warning('  Emotion #%d: SKIP - invalid/missing original image', er_id)
            failed_emotion_ids.add(er_id)
            continue

        push_record = {
            'Id': er_id,
            'CameraCode': CAMERA_CODE,
            'student_code': rec['student_no'],
            'SmallPic': small_pic_b64,
            'CaptureTime': ct_str,
            'ImageUrl': orig_img_b64,
            'Confidence': '%.2f' % (rec['dominant_confidence'] or 0),
            'score': round((rec['dominant_confidence'] or 0) * 100),
            'color': map_color(emotion_code),
            'Emotion': emotion_code,
            'GazeDirection': '',
            'created_at': ca_str,
        }
        batch.append((er_id, push_record))

        if show_detail:
            log.info('  Emotion #%d: SmallPic=%s ImageUrl=%s student=%s emotion=%s conf=%s',
                     er_id, base64_preview(small_pic_b64), base64_preview(orig_img_b64) if orig_img_b64 else '(empty)',
                     rec['student_no'], emotion_code, rec['dominant_confidence'])

        # 满一批或最后一条时发送
        if batch and (len(batch) >= batch_size or idx == total - 1):
            batch_ids = [b[0] for b in batch]
            batch_records = [b[1] for b in batch]
            payload_bytes = len(json.dumps(
                {'pageID': PAGE_ID, 'method': 'AddEmotion', 'emotions': batch_records},
                ensure_ascii=False))

            if audit_conn:
                audit_write_emotion_batch(
                    audit_conn, idx // batch_size, batch_records,
                    batch_ids[0], batch_ids[-1], payload_bytes)
                pushed += len(batch)
                for bid in batch_ids:
                    pushed_emotion_ids.add(bid)
                log.info('  Batch %d-%d: %d AUDITED (%d bytes)',
                         batch_ids[0], batch_ids[-1], len(batch), payload_bytes)
                batch = []
                continue

            payload = {
                'pageID': PAGE_ID,
                'method': 'AddEmotion',
                'emotions': batch_records,
            }
            resp = call_api(payload, dry_run)
            _response_log.info('[BATCH %d-%d] %s', batch_ids[0], batch_ids[-1],
                               json.dumps(resp, ensure_ascii=False)[:2000])

            if resp.get('success'):
                data = resp.get('data', {})
                api_failed = set(data.get('failed_ids', []) or [])
                api_inserted = data.get('inserted_ids', []) or []
                ok_count = data.get('inserted_count', len(batch_records) - len(api_failed))
                pushed += ok_count
                for bid in batch_ids:
                    (pushed_emotion_ids if bid not in api_failed else failed_emotion_ids).add(bid)
                confirmations.append({
                    'batch': '%d-%d' % (batch_ids[0], batch_ids[-1]),
                    'inserted_count': ok_count,
                    'inserted_ids': api_inserted[:20],
                    'failed_ids': list(api_failed)[:20],
                    'time': datetime.now().isoformat(),
                })
                log.info('  Batch %d-%d: %d OK (inserted=%s, failed=%s)',
                         batch_ids[0], batch_ids[-1], ok_count,
                         len(api_inserted or []), len(api_failed or []))
            else:
                errors += len(batch)
                log.warning('  Batch %d-%d: FAIL - %s', batch_ids[0], batch_ids[-1], resp.get('error', 'unknown'))

            batch = []

        if (idx + 1) % 1000 == 0:
            log.info('  Progress: %d/%d emotions', idx + 1, total)

    stats['emotions'] = stats.get('emotions', 0) + pushed
    stats['errors'] = stats.get('errors', 0) + errors
    log.info('Emotions pushed: %d, errors: %d', pushed, errors)
    return pushed_emotion_ids, confirmations


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description='推送到 ylcs.htface.cn')
    parser.add_argument('--students-only', action='store_true')
    parser.add_argument('--emotions-only', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--audit', action='store_true', help='写入本地审核表，不调用外部 API')
    parser.add_argument('--start-id', type=int, default=0)
    parser.add_argument('--max', type=int, default=0)
    parser.add_argument('--top', type=int, default=0)
    parser.add_argument('--batch', type=int, default=10, help='每批情绪条数（默认 10，远程服务器性能弱请勿设太高）')
    args = parser.parse_args()

    do_students = not args.emotions_only
    do_emotions = not args.students_only
    top = args.top if args.top > 0 else None

    log.info('=== External Push (PostgreSQL) ===')
    log.info('DB: %s:%d/%s  user=%s', DB_HOST, DB_PORT, DB_NAME, DB_USER)
    log.info('API: %s  page=%s  camera=%s', API_BASE, PAGE_ID, CAMERA_CODE)
    mode_str = 'AUDIT' if args.audit else ('DRY' if args.dry_run else 'LIVE')
    log.info('Mode: %s students=%s emotions=%s resume=%s top=%s',
             mode_str, do_students, do_emotions, args.resume, top or 'all')

    conn = db_connect()
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)

    # 审核模式：额外连接写入本地审计表
    audit_conn = db_connect() if args.audit else None

    # 审计和正式推送使用独立的 checkpoint 文件，互不干扰
    pushed_eids, pushed_sids, failed_eids, cp = load_checkpoint(args.audit)
    confirmations = list(cp.get('confirmations', []))

    if args.resume:
        stats = cp.get('stats', {'students': 0, 'emotions': 0, 'errors': 0})
        log.info('Resume: %d students, %d emotions done, %d failed',
                 len(pushed_sids), len(pushed_eids), len(failed_eids))
    else:
        # 非续传模式从零开始统计
        stats = {'students': 0, 'emotions': 0, 'errors': 0}
        pushed_eids, pushed_sids, failed_eids = set(), set(), set()

    start_ts = time.time()

    if do_students:
        pushed_sids = push_students(cur, args.dry_run, args.resume, stats, pushed_sids, top, audit_conn)
        save_checkpoint(pushed_eids, pushed_sids, failed_eids, confirmations, stats, args.audit)

    if do_emotions:
        pushed_eids, new_confs = push_emotions(
            cur, args.dry_run, args.resume, stats,
            pushed_eids, failed_eids,
            args.start_id or None, args.max or None, top, args.batch, audit_conn)
        confirmations.extend(new_confs)
        save_checkpoint(pushed_eids, pushed_sids, failed_eids, confirmations, stats, args.audit)

    elapsed = time.time() - start_ts
    total_ids = sum(len(c.get('inserted_ids', [])) for c in confirmations)

    log.info('\n=== Push Complete ===')
    log.info('Time:       %.1f min', elapsed / 60)
    log.info('Students:   %d', stats.get('students', 0))
    log.info('Emotions:   %d (confirmed: %d IDs)', stats.get('emotions', 0), total_ids)
    log.info('Failed:     %d', len(failed_eids))

    save_checkpoint(pushed_eids, pushed_sids, failed_eids, confirmations, stats, args.audit)

    if audit_conn:
        audit_conn.close()
    cur.close()
    conn.close()


if __name__ == '__main__':
    main()
