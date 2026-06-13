#!/usr/bin/env python3
"""
外部推送脚本：将本地情绪数据推送到 ylcs.htface.cn 外部平台
从 MySQL 读取学生+情绪数据，调用外部 API 的 updateStudent 和 AddEmotion 接口

Usage:
  python3 push_to_external.py                          # 全量推送
  python3 push_to_external.py --batch 100              # 每批 100 条
  python3 push_to_external.py --students-only           # 仅推送学生信息
  python3 push_to_external.py --emotions-only           # 仅推送情绪记录
  python3 push_to_external.py --dry-run                 # 干跑，不实际发送
  python3 push_to_external.py --resume                  # 断点续传
  python3 push_to_external.py --start-id 50000          # 从指定 emotion_record ID 开始
"""

import os, sys, json, time, base64
from datetime import datetime
import argparse
import logging

import requests
import pymysql

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

RESPONSE_LOG_FILE = '/tmp/external_push_responses.log'
response_log = logging.getLogger('push_responses')
response_log.setLevel(logging.INFO)
_rl_handler = logging.FileHandler(RESPONSE_LOG_FILE)
_rl_handler.setFormatter(logging.Formatter('%(asctime)s %(message)s'))
response_log.addHandler(_rl_handler)

# === Config ===
API_BASE = 'http://ylcs.htface.cn:33895/api/Page/Execute'
PAGE_ID = 'Emotion'
CAMERA_CODE = 'CAM_DEFAULT'
SERVER_BASE = 'http://localhost:8090'  # 后端地址，用于转换图片 URL

DB_HOST = 'craftsupport.cn'
DB_PORT = 3307
DB_USER = 'root'
DB_PASS = '123456'
DB_NAME = 'emotion_platform'

CHECKPOINT_FILE = '/tmp/external_push_checkpoint.json'
BASE64_DUMP_DIR = '/tmp/external_push_base64'  # base64 导出目录，供人工审核
BATCH_SIZE = 1          # AddEmotion 单批 1 条（含原始大图 base64 ~1.8MB）
CROP_PREFIX = 'images/cropped'  # 裁剪图路径前缀，用于 URL 转换

# 情绪映射：DB 值（先中文→英文）→ 外部 API 编码
EMOTION_CN_TO_EN = {
    '中性': 'neutral', '开心': 'happy', '伤心': 'sad', '生气': 'angry',
    '惊讶': 'surprise', '恐惧': 'fear', '厌恶': 'disgust', '蔑视': 'contempt',
}
EMOTION_TO_EXTERNAL = {
    'happy': 'happy',
    'sad': 'sad',
    'angry': 'angry',
    'disgust': 'angry',
    'surprise': 'surprised',
    'fear': 'fearful',
    'neutral': 'calm',
    'contempt': 'calm',
}
EMOTION_COLORS = {
    'happy': 'green', 'sad': 'blue', 'angry': 'red',
    'calm': 'cyan', 'surprised': 'yellow', 'fearful': 'purple',
}


def db_connect():
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=DB_NAME,
                           charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)


def load_checkpoint():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f:
            d = json.load(f)
        pushed_emotion = set(d.get('pushed_emotion_ids', []))
        pushed_student = set(d.get('pushed_student_ids', []))
        failed_emotion = set(d.get('failed_emotion_ids', []))
        return pushed_emotion, pushed_student, failed_emotion, d
    return set(), set(), set(), {
        'pushed_emotion_ids': [], 'pushed_student_ids': [], 'failed_emotion_ids': [],
        'confirmations': [],
        'stats': {'students': 0, 'emotions': 0, 'errors': 0}
    }


def save_checkpoint(emotion_ids, student_ids, failed_ids, confirmations, stats):
    data = {
        'pushed_emotion_ids': sorted(set(emotion_ids)),
        'pushed_student_ids': sorted(set(student_ids)),
        'failed_emotion_ids': sorted(set(failed_ids)),
        'confirmations': confirmations[-100:],  # keep last 100
        'stats': stats,
    }
    tmp = CHECKPOINT_FILE + '.tmp'
    with open(tmp, 'w') as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, CHECKPOINT_FILE)


def to_http_url(local_path):
    """本地文件路径 → 后端可访问的 HTTP URL"""
    if not local_path:
        return ''
    normalized = os.path.normpath(local_path).replace('\\', '/')
    idx = normalized.find('/' + CROP_PREFIX)
    if idx >= 0:
        return SERVER_BASE + normalized[idx:]
    idx2 = normalized.find('/images/')
    if idx2 >= 0:
        return SERVER_BASE + normalized[idx2:]
    idx3 = normalized.find('/data/')
    if idx3 >= 0:
        return SERVER_BASE + normalized[idx3:]
    return local_path


def image_file_to_base64(file_path, max_size=1024*1024, max_dim=None):
    """读取图片文件并返回 base64 编码字符串。max_dim 指定最长边像素（等比例缩放）。"""
    if not file_path:
        return ''
    try:
        if not os.path.exists(file_path):
            log.warning('Image file not found: %s', file_path)
            return ''
        with open(file_path, 'rb') as f:
            data = f.read()

        # 需要缩放时直接缩放（max_dim 由调用者指定，如 640）
        if max_dim:
            try:
                from PIL import Image
                import io
                img = Image.open(file_path)
                w, h = img.size
                if max(w, h) > max_dim:
                    ratio = max_dim / max(w, h)
                    new_size = (int(w * ratio), int(h * ratio))
                    img = img.resize(new_size, Image.LANCZOS)
                    buf = io.BytesIO()
                    img.save(buf, format='JPEG', quality=85)
                    data = buf.getvalue()
                    log.info('  Resized image %s: %dx%d -> %dx%d (%d KB)',
                             os.path.basename(file_path), w, h, new_size[0], new_size[1], len(data) // 1024)
            except ImportError:
                log.warning('PIL not available, cannot resize: %s', file_path)

        if len(data) > max_size:
            log.warning('Image too large (%d bytes), skipping: %s', len(data), file_path)
            return ''
        return base64.b64encode(data).decode('ascii')
    except Exception as e:
        log.warning('Failed to read image %s: %s', file_path, str(e)[:100])
        return ''


def validate_base64_image(b64_str):
    """验证 base64 字符串是否为有效的 JPEG/PNG/WebP 图片数据"""
    if not b64_str:
        return False
    try:
        data = base64.b64decode(b64_str)
        if len(data) < 12:
            return False
        # JPEG:  FF D8 FF
        # PNG:   89 50 4E 47
        # WebP:  52 49 46 46 xxxx 57 45 42 50
        is_valid = (
            data[:3] == b'\xff\xd8\xff' or
            data[:4] == b'\x89PNG' or
            (data[:4] == b'RIFF' and data[8:12] == b'WEBP')
        )
        if not is_valid:
            log.warning('Unknown image format (first 8 bytes: %s), rejecting', data[:8].hex())
        return is_valid
    except Exception as e:
        log.warning('Base64 decode failed: %s', str(e)[:100])
        return False


def map_emotion(db_label):
    """DB 情绪值 → 外部 API 编码"""
    if not db_label:
        return 'calm'
    en_label = EMOTION_CN_TO_EN.get(db_label, db_label)
    return EMOTION_TO_EXTERNAL.get(en_label, 'calm')


def map_color(emotion_code):
    return EMOTION_COLORS.get(emotion_code, '')


def base64_preview(b64_str, max_front=80, max_back=20):
    """生成 base64 预览用于日志（截取头尾，标注长度）"""
    if not b64_str:
        return '(empty)'
    if len(b64_str) <= max_front + max_back + 5:
        return b64_str
    return '%s...%s [%d bytes]' % (b64_str[:max_front], b64_str[-max_back:], len(b64_str))


def dump_base64(b64_str, student_no, image_index):
    """将 base64 内容写入日期目录下的 .txt 文件供人工审核"""
    if not b64_str:
        return ''
    dir_path = os.path.join(BASE64_DUMP_DIR, datetime.now().strftime('%Y%m%d'))
    os.makedirs(dir_path, exist_ok=True)
    file_path = os.path.join(dir_path, '%s_image_%d.txt' % (student_no, image_index))
    with open(file_path, 'w') as f:
        f.write(b64_str)
    return file_path


def call_api(payload, dry_run=False):
    """调用外部 API"""
    if dry_run:
        log.info('[DRY] Would POST: %s', json.dumps(
            {k: v for k, v in payload.items() if k != 'ImageUrl' and k != 'emotions'},
            ensure_ascii=False)[:500])
        return {'success': True, 'data': {}}

    method = payload.get('method', '?')
    student_code = payload.get('student_code', payload.get('studentCode', ''))

    # 提取 base64 预览（updateStudent: 顶层 ImageUrl；AddEmotion: emotions[].SmallPic/ImageUrl）
    emotions = payload.get('emotions', [])
    if emotions:
        n = len(emotions)
        small_previews = ', '.join(base64_preview(e.get('SmallPic', '')) for e in emotions[:2])
        img_previews = ', '.join(base64_preview(e.get('ImageUrl', '')) for e in emotions[:2])
        if n > 2:
            small_previews += ', ...'
            img_previews += ', ...'
        b64_previews = f'{n} records, SmallPic=[{small_previews}], ImageUrl=[{img_previews}]'
    else:
        img_b64s = payload.get('ImageUrl') or payload.get('SmallPic') or ''
        if isinstance(img_b64s, list):
            b64_previews = ', '.join(base64_preview(b) for b in img_b64s[:3])
            if len(img_b64s) > 3:
                b64_previews += ', ...'
        else:
            b64_previews = base64_preview(img_b64s)
    log.info('  => POST %s %s (payload ~%d bytes, images: %s)',
             method, student_code or '', len(json.dumps(payload, ensure_ascii=False)),
             b64_previews)

    for attempt in range(3):
        try:
            r = requests.post(API_BASE, json=payload, timeout=120)
            resp_text = r.text
            resp = r.json()
            if resp.get('success'):
                log.info('  <= %s OK: %s', method, json.dumps(resp, ensure_ascii=False)[:300])
                return resp
            log.warning('  <= %s attempt %d FAILED: status=%s, body=%s',
                        method, attempt + 1, r.status_code, resp_text[:500])
            if attempt < 2:
                time.sleep(3)
        except Exception as e:
            log.warning('  <= %s attempt %d ERROR: %s',
                        method, attempt + 1, str(e)[:200])
            if attempt < 2:
                time.sleep(5)
    return {'success': False, 'error': 'max retries exceeded'}


def push_students(cursor, dry_run, resume, stats, pushed_student_ids, top=None):
    """推送学生信息（含人脸照片 URL）。top=N 时只推送人脸数最多的 N 个学生"""
    if top:
        cursor.execute("""
            SELECT s.id, s.student_no, s.name, COUNT(fr.id) as face_cnt
            FROM student s
            JOIN face_record fr ON fr.student_id = s.id
            GROUP BY s.id, s.student_no, s.name
            ORDER BY face_cnt DESC
            LIMIT %s
        """, (top,))
    else:
        cursor.execute('SELECT id, student_no, name, 0 as face_cnt FROM student ORDER BY id')
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

        # 取该学生第一张裁剪图，读取、base64 编码并校验
        cursor.execute(
            "SELECT cropped_image_url FROM face_record WHERE student_id=%s AND cropped_image_url IS NOT NULL LIMIT 1",
            (sid,))
        raw_fetch = cursor.fetchall()
        image_b64s = []
        skipped_files = 0
        for r in raw_fetch:
            b64 = image_file_to_base64(r['cropped_image_url'])
            if not b64:
                skipped_files += 1
                continue
            if validate_base64_image(b64):
                image_b64s.append(b64)
            else:
                log.warning('  Student #%d %s: image base64 validation failed, skipping: %s',
                            sid, sno, r['cropped_image_url'])
                skipped_files += 1

        if not image_b64s:
            log.warning('  Student #%d %s: SKIP - no valid images (%d files skipped)',
                        sid, sno, skipped_files)
            continue

        if skipped_files > 0:
            log.info('  Student #%d %s: %d valid, %d skipped', sid, sno, len(image_b64s), skipped_files)

        # 写入 .txt 文件供人工审核 + 日志预览
        for i, b64 in enumerate(image_b64s):
            dump_path = dump_base64(b64, sno, i)
            log.info('  Student #%d %s: image[%d] base64 %s -> %s',
                     sid, sno, i, base64_preview(b64), dump_path)

        payload = {
            'pageID': PAGE_ID,
            'method': 'updateStudent',
            'student_code': sno,
            'student_name': name,
            'ImageUrl': image_b64s,
        }
        resp = call_api(payload, dry_run)

        if resp.get('success'):
            pushed += 1
            pushed_student_ids.add(sid)
            log.info('  Student #%d %s: OK (%d images)', sid, sno, len(image_b64s))
        else:
            errors += 1
            log.warning('  Student #%d %s: FAIL - %s', sid, sno, resp.get('error', 'unknown'))
        response_log.info('[updateStudent %s] %s', sno,
                          json.dumps(resp, ensure_ascii=False)[:500])

    stats['students'] = stats.get('students', 0) + pushed
    stats['errors'] = stats.get('errors', 0) + errors
    log.info('Students pushed: %d, errors: %d', pushed, errors)
    return pushed_student_ids, []  # no confirmations for students


def push_emotions(cursor, dry_run, resume, stats, pushed_emotion_ids, failed_emotion_ids,
                  start_id=None, max_count=None, top=None):
    """推送情绪识别记录。top=N 时只推送人脸数最多的 N 个学生对应的情绪"""
    query = """
        SELECT er.id, er.face_record_id, er.dominant_emotion, er.dominant_confidence,
               er.created_at, fr.cropped_image_url, fr.student_id,
               s.student_no, ci.capture_time, ci.image_url
        FROM emotion_record er
        JOIN face_record fr ON fr.id = er.face_record_id
        LEFT JOIN student s ON s.id = fr.student_id
        JOIN class_image ci ON ci.id = fr.class_image_id
        WHERE s.student_no IS NOT NULL
    """
    params = []
    if top:
        cursor.execute("""
            SELECT s2.id FROM student s2
            JOIN face_record fr2 ON fr2.student_id = s2.id
            GROUP BY s2.id ORDER BY COUNT(fr2.id) DESC LIMIT %s
        """, (top,))
        top_ids = [r['id'] for r in cursor.fetchall()]
        if top_ids:
            query += ' AND fr.student_id IN (%s)' % ','.join(['%s'] * len(top_ids))
            params.extend(top_ids)
    if start_id:
        query += ' AND er.id >= %s'
        params.append(start_id)
    query += ' ORDER BY er.id ASC'
    if max_count:
        query += ' LIMIT %s'
        params.append(max_count)

    log.debug('Emotion query SQL: %s | params: %s', query[:200], params[:20])
    try:
        cursor.execute(query, params)
        records = cursor.fetchall()
    except Exception as e:
        log.error('Emotion query failed: %s', e)
        log.error('SQL: %s', query[:500])
        log.error('Params: %s', params[:50])
        raise
    log.info('Pushing %d emotion records...', len(records))

    if resume and pushed_emotion_ids:
        records = [r for r in records if r['id'] not in pushed_emotion_ids]

    confirmations = []  # 存储 API 响应确认记录，用于后续审计
    batch = []
    pushed = 0
    errors = 0
    total = len(records)

    for idx, rec in enumerate(records):
        er_id = rec['id']
        emotion_code = map_emotion(rec['dominant_emotion'])
        capture_time = rec['capture_time']
        if capture_time:
            ct_str = capture_time.strftime('%Y-%m-%d %H:%M:%S') if hasattr(capture_time, 'strftime') else str(capture_time)[:19]
        else:
            ct_str = ''
        created_at = rec['created_at']
        if created_at:
            ca_str = created_at.strftime('%Y-%m-%d %H:%M:%S') if hasattr(created_at, 'strftime') else str(created_at)[:19]
        else:
            ca_str = ''

        # 前 3 条显示详细日志
        show_detail = idx < 3

        # SmallPic: 裁剪图 base64；ImageUrl: 原始大图 base64
        if show_detail:
            log.info('  Emotion #%d: reading cropped=%s', er_id, rec['cropped_image_url'])
        small_pic_b64 = image_file_to_base64(rec['cropped_image_url'], max_size=5*1024*1024)
        if show_detail:
            log.info('  Emotion #%d: reading original=%s', er_id, rec['image_url'])
        orig_img_b64 = image_file_to_base64(rec['image_url'], max_size=5*1024*1024, max_dim=640)

        valid_small = bool(small_pic_b64) and validate_base64_image(small_pic_b64)
        valid_orig = bool(orig_img_b64) and validate_base64_image(orig_img_b64)

        if not valid_small:
            log.warning('  Emotion #%d: SKIP - invalid/missing cropped image', er_id)
            failed_emotion_ids.add(er_id)
        else:
            push_record = {
                'Id': er_id,
                'CameraCode': CAMERA_CODE,
                'student_code': rec['student_no'],
                'SmallPic': small_pic_b64,
                'CaptureTime': ct_str,
                'ImageUrl': orig_img_b64 if valid_orig else '',
                'Confidence': '%.2f' % (rec['dominant_confidence'] or 0),
                'score': round((rec['dominant_confidence'] or 0) * 100),
                'color': map_color(emotion_code),
                'Emotion': emotion_code,
                'GazeDirection': '',
                'created_at': ca_str,
            }
            batch.append((er_id, push_record))
            if show_detail:
                log.info('  Emotion #%d: SmallPic b64=%s, ImageUrl b64=%s, student=%s, emotion=%s, confidence=%s',
                         er_id, base64_preview(small_pic_b64), base64_preview(orig_img_b64) if valid_orig else '(empty)',
                         rec['student_no'], emotion_code, rec['dominant_confidence'])

        # 满一批或最后一条时发送（空 batch 不发送）
        if batch and (len(batch) >= BATCH_SIZE or idx == total - 1):
            batch_ids = [b[0] for b in batch]
            batch_records = [b[1] for b in batch]

            payload = {
                'pageID': PAGE_ID,
                'method': 'AddEmotion',
                'emotions': batch_records,
            }
            resp = call_api(payload, dry_run)

            # API 响应写入独立日志文件
            response_log.info('[BATCH %d-%d] %s',
                              batch_ids[0], batch_ids[-1],
                              json.dumps(resp, ensure_ascii=False)[:2000])

            if resp.get('success'):
                data = resp.get('data', {})
                api_failed = data.get('failed_ids', []) or []
                api_inserted = data.get('inserted_ids', []) or []
                ok_count = data.get('inserted_count', len(batch_records) - len(api_failed))
                pushed += ok_count
                for bid in batch_ids:
                    if bid not in api_failed:
                        pushed_emotion_ids.add(bid)
                    else:
                        failed_emotion_ids.add(bid)
                # Store API response confirmation
                confirmations.append({
                    'batch': '%d-%d' % (batch_ids[0], batch_ids[-1]),
                    'inserted_count': ok_count,
                    'inserted_ids': api_inserted[:20] if api_inserted else [],
                    'failed_ids': api_failed[:20] if api_failed else [],
                    'error_details': data.get('error_details', {}),
                    'api_response': data,
                    'time': datetime.now().isoformat(),
                })
                log.info('  Batch %d-%d: %d OK (inserted=%s, failed=%s)',
                         batch_ids[0], batch_ids[-1], ok_count,
                         len(api_inserted or []), len(api_failed or []))
            else:
                errors += len(batch)
                log.warning('  Batch %d-%d: FAIL - %s',
                            batch_ids[0], batch_ids[-1], resp.get('error', 'unknown'))

            batch = []

        # 每 1000 条输出进度
        if (idx + 1) % 1000 == 0:
            log.info('  Progress: %d/%d emotions (batch=%d-%d)',
                     idx + 1, total, batch_ids[0] if batch_ids else 0, batch_ids[-1] if batch_ids else 0)

    stats['emotions'] = stats.get('emotions', 0) + pushed
    stats['errors'] = stats.get('errors', 0) + errors
    log.info('Emotions pushed: %d, errors: %d', pushed, errors)
    return pushed_emotion_ids, confirmations


def main():
    parser = argparse.ArgumentParser(description='External Push: 推送学生+情绪数据到 ylcs.htface.cn')
    parser.add_argument('--students-only', action='store_true', help='仅推送学生信息')
    parser.add_argument('--emotions-only', action='store_true', help='仅推送情绪记录')
    parser.add_argument('--dry-run', action='store_true', help='干跑，不实际发送')
    parser.add_argument('--resume', action='store_true', help='断点续传')
    parser.add_argument('--start-id', type=int, default=0, help='从指定 emotion_record ID 开始')
    parser.add_argument('--max', type=int, default=0, help='最多推送 N 条情绪记录')
    parser.add_argument('--top', type=int, default=0, help='只推送人脸数最多的 N 个学生（默认全部）')
    parser.add_argument('--batch', type=int, default=1, help='每批条数（默认 1）')
    parser.add_argument('--server', default='http://localhost:8090', help='后端地址（用于图片 URL 转换）')
    parser.add_argument('--camera', default='CAM_DEFAULT', help='摄像头编码')
    args = parser.parse_args()

    global BATCH_SIZE, SERVER_BASE, CAMERA_CODE
    BATCH_SIZE = args.batch
    SERVER_BASE = args.server.rstrip('/')
    CAMERA_CODE = args.camera

    do_students = not args.emotions_only
    do_emotions = not args.students_only

    log.info('External Push')
    log.info('API: %s, server: %s, camera: %s', API_BASE, SERVER_BASE, CAMERA_CODE)
    log.info('Mode: students=%s, emotions=%s, dry_run=%s, resume=%s',
             do_students, do_emotions, args.dry_run, args.resume)

    db = db_connect()
    cursor = db.cursor()

    pushed_emotion_ids, pushed_student_ids, failed_emotion_ids, cp_data = load_checkpoint()
    stats = cp_data.get('stats', {'students': 0, 'emotions': 0, 'errors': 0})
    all_confirmations = list(cp_data.get('confirmations', []))

    if args.resume:
        log.info('Resume: %d students, %d emotions already pushed, %d failed',
                 len(pushed_student_ids), len(pushed_emotion_ids), len(failed_emotion_ids))

    start_time = time.time()

    top = args.top if args.top > 0 else None
    if top:
        log.info('Top N mode: only top %d students by face count', top)

    if do_students:
        pushed_student_ids, _ = push_students(cursor, args.dry_run, args.resume, stats, pushed_student_ids, top)
        save_checkpoint(pushed_emotion_ids, pushed_student_ids, failed_emotion_ids, all_confirmations, stats)

    if do_emotions:
        pushed_emotion_ids, confirmations = push_emotions(
            cursor, args.dry_run, args.resume, stats,
            pushed_emotion_ids, failed_emotion_ids, args.start_id or None, args.max or None, top)
        all_confirmations.extend(confirmations)
        save_checkpoint(pushed_emotion_ids, pushed_student_ids, failed_emotion_ids, all_confirmations, stats)

    elapsed = time.time() - start_time

    # Summary
    total_confirmed = len(all_confirmations)
    total_ids = sum(len(c.get('inserted_ids', [])) for c in all_confirmations)
    total_failed = len(failed_emotion_ids)

    log.info('\n=== Push Complete ===')
    log.info('Time:         %.1f min', elapsed / 60)
    log.info('Students:     %d', stats.get('students', 0))
    log.info('Emotions:     %d (confirmed: %d IDs)', stats.get('emotions', 0), total_ids)
    log.info('Failed:       %d', total_failed)
    log.info('Batch confs:  %d', total_confirmed)

    # Save final checkpoint
    save_checkpoint(pushed_emotion_ids, pushed_student_ids, failed_emotion_ids, all_confirmations, stats)

    cursor.close()
    db.close()


if __name__ == '__main__':
    main()
