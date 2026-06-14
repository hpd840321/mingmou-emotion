#!/usr/bin/env python3
"""
人脸性别属性批量补提取脚本
==========================
从已有的 class_image 源图重新调用 gRPC Analyze，
提取 FaceAttribute.gender 并更新到 face_record 表。

用法:
  python3 scripts/extract_gender.py
  python3 scripts/extract_gender.py --dry-run
  python3 scripts/extract_gender.py --max-images 50

输出:
  report_gender_results.json — 提取结果统计
  report_gender_inconsistency.csv — 同 student 性别不一致的记录
"""

import os, sys, json, time, math, struct
import argparse
import logging
import concurrent.futures
from datetime import datetime

import numpy as np
import grpc
import psycopg2
import psycopg2.extras

sys.path.insert(0, '/tmp/proto_out')
from inference_pb2_grpc import FaceServiceStub
from inference_pb2 import FaceAnalysisRequest

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('extract_gender')

# ============================================================
#  配置
# ============================================================
DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')
GRPC_HOST = os.environ.get('GRPC_HOST', 'localhost:50053')
GRPC_TIMEOUT = 120
BATCH_SIZE = 50          # 每提交多少条记录写一次 DB
IOU_MATCH_THRESHOLD = 0.3  # bbox IoU 匹配阈值

OUTPUT_DIR = '/media/zebra/data/官渡一中初一班-0526'


# ============================================================
#  gRPC 工具
# ============================================================
def get_grpc_stub():
    channel = grpc.insecure_channel(GRPC_HOST,
        options=[('grpc.max_send_message_length', 50*1024*1024),
                 ('grpc.max_receive_message_length', 50*1024*1024)])
    return FaceServiceStub(channel)


def call_analyze(stub, image_bytes):
    """调用 gRPC Analyze (仅 ATTRIBUTE，获取 gender)"""
    req = FaceAnalysisRequest(image_data=image_bytes, enabled_features=0x10)
    resp = stub.Analyze(req, timeout=GRPC_TIMEOUT)
    if not resp.success:
        return []
    faces = []
    for f in resp.faces:
        tok = f.token
        face = {
            'bbox': [int(tok.x), int(tok.y), int(tok.width), int(tok.height)],
            'confidence': tok.confidence,
            'quality': f.quality,
            'gender': f.attribute.gender if f.HasField('attribute') else None,
        }
        faces.append(face)
    return faces


def bbox_iou(a, b):
    """计算两个 bbox [x,y,w,h] 的 IoU"""
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    # 交集
    ix = max(ax, bx)
    iy = max(ay, by)
    iw = min(ax + aw, bx + bw) - ix
    ih = min(ay + ah, by + bh) - iy
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    # 并集
    area_a = aw * ah
    area_b = bw * bh
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def parse_bbox(bbox_json):
    d = json.loads(bbox_json) if isinstance(bbox_json, str) else bbox_json
    return [d['x'], d['y'], d['width'], d['height']]


# ============================================================
#  DB 工具
# ============================================================
def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)


def get_images_to_process(cur, max_images=None):
    """获取需要处理的 class_image 列表"""
    limit_clause = f"LIMIT {max_images}" if max_images else ""
    cur.execute(f"""
        SELECT ci.id, ci.image_url
        FROM class_image ci
        WHERE EXISTS (
            SELECT 1 FROM face_record fr
            WHERE fr.class_image_id = ci.id AND fr.gender IS NULL
        )
        ORDER BY ci.id
        {limit_clause}
    """)
    return cur.fetchall()


def get_face_records_for_image(cur, ci_id):
    """获取某个 class_image 的所有 face_record (id, bbox)"""
    cur.execute("""
        SELECT id, bbox FROM face_record
        WHERE class_image_id = %s AND gender IS NULL
    """, (ci_id,))
    return cur.fetchall()


def update_gender(cur, fr_id, gender):
    """更新单条 face_record 的 gender"""
    cur.execute("UPDATE face_record SET gender = %s WHERE id = %s",
                (gender, fr_id))


# ============================================================
#  主处理
# ============================================================

def process_image_worker(ci_id, image_url):
    """并发 worker：独立 gRPC stub + DB 连接，处理一张图片后返回统计"""
    stub = get_grpc_stub()
    conn = db_connect()
    cur = conn.cursor()
    try:
        if not os.path.exists(image_url):
            return (ci_id, 0, 0, f"file not found: {image_url}")
        with open(image_url, 'rb') as f:
            img_bytes = f.read()
        detected = call_analyze(stub, img_bytes)
        if not detected:
            return (ci_id, 0, 0, None)
        existing = get_face_records_for_image(cur, ci_id)
        if not existing:
            return (ci_id, len(detected), 0, None)
        matched = 0
        for dface in detected:
            db = dface['bbox']
            gender = dface['gender']
            if gender is None:
                continue
            best_iou = 0
            best_fr_id = None
            for fr_id, bbox_json in existing:
                eb = parse_bbox(bbox_json)
                iou = bbox_iou(db, eb)
                if iou > best_iou:
                    best_iou = iou
                    best_fr_id = fr_id
            if best_iou >= IOU_MATCH_THRESHOLD and best_fr_id:
                update_gender(cur, best_fr_id, gender)
                matched += 1
        conn.commit()
        return (ci_id, len(detected), matched, None)
    except Exception as e:
        conn.rollback()
        return (ci_id, 0, 0, str(e))
    finally:
        cur.close()
        conn.close()


def find_inconsistency(cur):
    """找出同 student_id 下 gender 不一致的记录"""
    log.info("分析性别不一致...")
    rows = cur.execute("""
        SELECT fr.student_id, s.name, fr.gender, COUNT(*) as cnt
        FROM face_record fr
        JOIN student s ON fr.student_id = s.id
        WHERE fr.student_id IS NOT NULL AND fr.gender IS NOT NULL
        GROUP BY fr.student_id, s.name, fr.gender
        ORDER BY fr.student_id, fr.gender
    """)
    rows = cur.fetchall()

    # 按 student_id 分组
    from collections import defaultdict
    student_genders = defaultdict(list)
    for sid, sname, gender, cnt in rows:
        student_genders[(sid, sname)].append((gender, cnt))

    inconsistencies = []
    for (sid, sname), genders in student_genders.items():
        if len(genders) >= 2:
            total = sum(c for _, c in genders)
            inconsistencies.append({
                'student_id': sid,
                'name': sname,
                'genders': {str(g): c for g, c in genders},
                'total': total,
                'female_pct': round(
                    sum(c for g, c in genders if g == 0) / total * 100, 1
                ) if any(g == 0 for g, _ in genders) else 0,
                'male_pct': round(
                    sum(c for g, c in genders if g == 1) / total * 100, 1
                ) if any(g == 1 for g, _ in genders) else 0,
            })

    inconsistencies.sort(key=lambda x: -min(x['female_pct'], x['male_pct']))
    return inconsistencies


def export_csv(filename, data, fieldnames):
    import csv
    path = os.path.join(OUTPUT_DIR, filename)
    with open(path, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, extrasaction='ignore')
        w.writeheader()
        w.writerows(data)
    log.info("  Exported %s: %d rows", filename, len(data))


def main():
    parser = argparse.ArgumentParser(description='补提取人脸性别属性')
    parser.add_argument('--dry-run', action='store_true', help='干跑，不写入 DB')
    parser.add_argument('--max-images', type=int, default=None, help='最多处理多少张图片')
    parser.add_argument('--skip-extract', action='store_true', help='跳过提取，只分析已有数据的不一致性')
    args = parser.parse_args()

    conn = db_connect()
    cur = conn.cursor()

    # ----- 统计 -----
    cur.execute("SELECT COUNT(*) FROM face_record WHERE gender IS NOT NULL")
    already = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM face_record")
    total = cur.fetchone()[0]
    log.info("已有性别: %s / %s 条 face_record", already, total)

    if not args.skip_extract and already < total:
        images = get_images_to_process(cur, args.max_images)
        log.info("待处理图片: %d 张", len(images))

        stats = {'processed': 0, 'detected': 0, 'matched': 0, 'errors': 0,
                 'start_time': time.time()}
        total_images = len(images)

        # 并发处理：3 个 worker，各自拥有独立 gRPC stub + DB 连接
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            fut_map = {}
            for ci_id, image_url in images:
                fut = pool.submit(process_image_worker, ci_id, image_url)
                fut_map[fut] = (ci_id, image_url)

            for idx, fut in enumerate(concurrent.futures.as_completed(fut_map)):
                ci_id, n_detected, n_matched, error = fut.result()
                stats['processed'] += 1
                stats['detected'] += n_detected
                stats['matched'] += n_matched
                if error:
                    stats['errors'] += 1

                if (idx + 1) % BATCH_SIZE == 0:
                    elapsed = time.time() - stats['start_time']
                    rate = (idx + 1) / elapsed if elapsed > 0 else 0
                    log.info("  Progress: %d/%d images, %.1f img/min, %d matched",
                             idx + 1, total_images, rate * 60, stats['matched'])

        elapsed = time.time() - stats['start_time']
        log.info("=" * 60)
        log.info("提取完成!")
        log.info("  处理: %d 张图片", stats['processed'])
        log.info("  检测: %d 张人脸", stats['detected'])
        log.info("  匹配: %d 条 face_record", stats['matched'])
        log.info("  错误: %d", stats['errors'])
        log.info("  耗时: %.0fs (%.1f min)", elapsed, elapsed / 60)

        summary = {
            'generated_at': datetime.now().isoformat(),
            'images_processed': stats['processed'],
            'faces_detected': stats['detected'],
            'face_records_matched': stats['matched'],
            'errors': stats['errors'],
            'elapsed_seconds': round(elapsed),
            'avg_time_per_image': round(elapsed / max(stats['processed'], 1), 2),
        }
        path = os.path.join(OUTPUT_DIR, 'report_gender_results.json')
        with open(path, 'w') as f:
            json.dump(summary, f, indent=2)
        log.info("  -> %s", path)

    # ----- 性别不一致分析 -----
    inconsistencies = find_inconsistency(cur)

    export_csv('report_gender_inconsistency.csv', inconsistencies, [
        'student_id', 'name', 'genders', 'total',
        'female_pct', 'male_pct',
    ])

    # 输出概览
    log.info("=" * 60)
    log.info("性别不一致分析:")
    log.info("  总计: %d 个学生有 gender 混合", len(inconsistencies))
    if inconsistencies:
        log.info("  Top 20 最可疑（两种性别占比最接近）:")
        for inc in inconsistencies[:20]:
            log.info("    %s(#%s): 女=%s%% 男=%s%%  total=%s  genders=%s",
                     inc['name'], inc['student_id'],
                     inc['female_pct'], inc['male_pct'],
                     inc['total'], inc['genders'])

        # 过滤出两种性别占比都 > 20% 的（最可能是匹配错误）
        mixed = [i for i in inconsistencies
                 if i['female_pct'] > 20 and i['male_pct'] > 20]
        log.info("  两种性别占比均 > 20%% 的严重混合: %d 个学生", len(mixed))
        for inc in mixed[:10]:
            log.info("    %s(#%s): 女=%s%% 男=%s%% total=%s",
                     inc['name'], inc['student_id'],
                     inc['female_pct'], inc['male_pct'], inc['total'])
    log.info("=" * 60)

    cur.close()
    conn.close()


if __name__ == '__main__':
    main()
