#!/usr/bin/env python3
"""
空间位置辅助人脸匹配校准脚本（阶段 A）
========================================
基于已有 face_record 的 bbox 空间位置，进行：
  1. 每个学生的座位分布模型构建
  2. 空间异常匹配检测（特征匹配但座位不符 -> 疑似误召回）
  3. 未归属人脸的空间归属建议（student_id IS NULL -> 建议学生）
  4. 不同学生的座位重叠检测（可能重复/合并）
  5. 输出 CSV 报告供人工审查

用法:
  python3 scripts/spatial_calibration.py
  python3 scripts/spatial_calibration.py --help

输出:
  report_student_seat_map.csv    — 每个学生的座位统计
  report_spatial_outliers.csv    — 空间异常匹配列表
  report_missed_links.csv        — 建议关联的未归属人脸
  report_seat_overlap.csv        — 座位重叠的学生对
  report_summary.json            — 汇总统计
"""

import os, sys, json, math, csv
import argparse
import logging
from datetime import datetime

import psycopg2
import psycopg2.extras

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('spatial_calib')

# ============================================================
#  配置
# ============================================================
DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')

SPATIAL_THRESHOLD = 200       # 同节次座位匹配阈值 (px)
OUTLIER_Z_SCORE = 2.5          # 异常检测 z-score 阈值
MIN_FACES_FOR_MODEL = 3        # 构建座位模型所需最少人脸数
CROSS_PERIOD_PENALTY = 1.5     # 跨节次距离容忍倍数（跨节次时标准放宽）

OUTPUT_DIR = os.environ.get('OUTPUT_DIR', '/media/zebra/data/官渡一中初一班-0526')


# ============================================================
#  DB 工具
# ============================================================
def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)


def query_all(cur, sql, params=None):
    cur.execute(sql, params or ())
    return cur.fetchall()


# ============================================================
#  核心
# ============================================================

def parse_bbox(bbox_json):
    """从 JSON bbox 字符串解析出 (cx, cy, x, y, w, h)"""
    d = json.loads(bbox_json) if isinstance(bbox_json, str) else bbox_json
    x, y, w, h = d['x'], d['y'], d['width'], d['height']
    cx = x + w // 2
    cy = y + h // 2
    return cx, cy, x, y, w, h


def build_seat_models(cur):
    """
    Step 1: 为每个有足够 face_record 的学生构建座位模型。
    返回 dict: student_id -> {cx, cy, std_cx, std_cy, radius, face_count, period_models}
    """
    log.info("构建座位模型...")

    rows = query_all(cur, """
        SELECT fr.student_id,
               ((fr.bbox::json->>'x')::numeric + (fr.bbox::json->>'width')::numeric / 2)::int AS cx,
               ((fr.bbox::json->>'y')::numeric + (fr.bbox::json->>'height')::numeric / 2)::int AS cy,
               ci.period_label,
               ci.capture_time::text AS capture_time
        FROM face_record fr
        JOIN class_image ci ON fr.class_image_id = ci.id
        WHERE fr.student_id IS NOT NULL
          AND fr.bbox IS NOT NULL
          AND fr.bbox != ''
    """)

    # 按 student_id 分组收集座位点
    student_seats = {}
    for sid, cx, cy, period, ts in rows:
        if sid not in student_seats:
            student_seats[sid] = {'all': [], 'by_period': {}}
        student_seats[sid]['all'].append((cx, cy, period, ts))
        if period not in student_seats[sid]['by_period']:
            student_seats[sid]['by_period'][period] = []
        student_seats[sid]['by_period'][period].append((cx, cy, ts))

    # 计算统计
    models = {}
    for sid, data in student_seats.items():
        points = data['all']
        n = len(points)
        if n < MIN_FACES_FOR_MODEL:
            continue

        cxs = [p[0] for p in points]
        cys = [p[1] for p in points]
        avg_cx = sum(cxs) / n
        avg_cy = sum(cys) / n
        var_cx = sum((x - avg_cx) ** 2 for x in cxs) / n
        var_cy = sum((y - avg_cy) ** 2 for y in cys) / n
        std_cx = math.sqrt(var_cx)
        std_cy = math.sqrt(var_cy)
        radius = max(std_cx, std_cy) * OUTLIER_Z_SCORE

        # 各节次的单独模型
        period_models = {}
        for period, period_points in data['by_period'].items():
            if len(period_points) < 2:
                continue
            pcx = [p[0] for p in period_points]
            pcy = [p[1] for p in period_points]
            pn = len(period_points)
            pavg_cx = sum(pcx) / pn
            pavg_cy = sum(pcy) / pn
            pvar_cx = sum((x - pavg_cx) ** 2 for x in pcx) / pn
            pvar_cy = sum((y - pavg_cy) ** 2 for y in pcy) / pn
            period_models[period] = {
                'cx': pavg_cx, 'cy': pavg_cy,
                'std': max(math.sqrt(pvar_cx), math.sqrt(pvar_cy)),
                'n': pn
            }

        models[sid] = {
            'cx': avg_cx, 'cy': avg_cy,
            'std_cx': std_cx, 'std_cy': std_cy,
            'radius': radius,
            'face_count': n,
            'period_models': period_models,
        }

    log.info("  构建完成: %d/%d 学生有足够数据 (%d faces, min=%d)",
             len(models), len(student_seats),
             sum(m['face_count'] for m in models.values()),
             MIN_FACES_FOR_MODEL)
    return models


def detect_outliers(cur, models):
    """
    Step 2: 检测空间异常匹配。
    对每个学生，找出那些远离其 seat 中心的 face_record。
    输出: [(face_record_id, student_id, dist, radius, ratio, cx, cy, seat_cx, seat_cy, period)]
    """
    log.info("检测空间异常匹配...")

    rows = query_all(cur, """
        SELECT fr.id, fr.student_id,
               ((fr.bbox::json->>'x')::numeric + (fr.bbox::json->>'width')::numeric / 2)::int AS cx,
               ((fr.bbox::json->>'y')::numeric + (fr.bbox::json->>'height')::numeric / 2)::int AS cy,
               ci.period_label,
               ci.capture_time::text AS capture_time
        FROM face_record fr
        JOIN class_image ci ON fr.class_image_id = ci.id
        WHERE fr.student_id IS NOT NULL
          AND fr.bbox IS NOT NULL AND fr.bbox != ''
    """)

    # 也可以按 period 分组统计每个学生的 period 内距离
    outliers = []
    for fr_id, sid, cx, cy, period, ts in rows:
        model = models.get(sid)
        if not model:
            continue

        # 计算到座位中心的距离
        dist = math.hypot(cx - model['cx'], cy - model['cy'])
        ratio = dist / model['radius'] if model['radius'] > 0 else 0

        # 同节次检测（更严格）
        period_model = model['period_models'].get(period)
        if period_model and period_model['n'] >= 2:
            period_dist = math.hypot(cx - period_model['cx'], cy - period_model['cy'])
            period_std = period_model['std'] if period_model['std'] > 0 else 1
            period_ratio = period_dist / (period_std * OUTLIER_Z_SCORE)
        else:
            period_dist = dist
            period_ratio = ratio

        is_outlier = (period_ratio > 1.0 and dist > SPATIAL_THRESHOLD)

        if is_outlier:
            outliers.append({
                'face_record_id': fr_id,
                'student_id': sid,
                'dist': round(dist, 1),
                'radius': round(model['radius'], 1),
                'ratio': round(ratio, 2),
                'period_ratio': round(period_ratio, 2),
                'cx': cx,
                'cy': cy,
                'seat_cx': round(model['cx'], 1),
                'seat_cy': round(model['cy'], 1),
                'period': period or 'unknown',
                'capture_time': ts,
            })

    outliers.sort(key=lambda x: -x['period_ratio'])
    log.info("  发现 %d 个空间异常匹配", len(outliers))
    return outliers


def suggest_links(cur, models):
    """
    Step 3: 对未关联人脸，空间推测应归属的学生。
    输出: [(face_record_id, suggested_student_id, confidence, dist, cx, cy, ...)]
    """
    log.info("推测未关联人脸的空间归属...")

    rows = query_all(cur, """
        SELECT fr.id,
               ((fr.bbox::json->>'x')::numeric + (fr.bbox::json->>'width')::numeric / 2)::int AS cx,
               ((fr.bbox::json->>'y')::numeric + (fr.bbox::json->>'height')::numeric / 2)::int AS cy,
               ci.period_label,
               ci.capture_time::text AS capture_time
        FROM face_record fr
        JOIN class_image ci ON fr.class_image_id = ci.id
        WHERE fr.student_id IS NULL
          AND fr.bbox IS NOT NULL AND fr.bbox != ''
    """)

    suggestions = []
    for fr_id, cx, cy, period, ts in rows:
        best = None
        best_dist = float('inf')

        for sid, model in models.items():
            # 优先同节次的 period 模型
            period_model = model['period_models'].get(period)
            if period_model:
                dist = math.hypot(cx - period_model['cx'], cy - period_model['cy'])
                threshold = max(period_model['std'] * OUTLIER_Z_SCORE * 2, SPATIAL_THRESHOLD)
            else:
                dist = math.hypot(cx - model['cx'], cy - model['cy'])
                threshold = model['radius'] * CROSS_PERIOD_PENALTY

            if dist < threshold and dist < best_dist:
                best_dist = dist
                confidence = max(0, min(100, int((1 - dist / threshold) * 100)))
                best = {
                    'student_id': sid,
                    'dist': round(dist, 1),
                    'threshold': round(threshold, 1),
                    'confidence': confidence,
                    'period': period or 'unknown',
                }

        if best:
            suggestions.append({
                'face_record_id': fr_id,
                'cx': cx,
                'cy': cy,
                'capture_time': ts,
                **best
            })

    suggestions.sort(key=lambda x: -x['confidence'])
    log.info("  建议关联 %d 条未归属人脸", len(suggestions))
    return suggestions


def detect_seat_overlap(models):
    """
    Step 4: 检测座位高度重叠的学生对（可能是同一个人的 duplicate）。
    输出: [(student_a, student_b, overlap_score, dist, ...)]
    """
    log.info("检测座位重叠的学生对...")
    overlaps = []
    student_ids = list(models.keys())

    for i in range(len(student_ids)):
        for j in range(i + 1, len(student_ids)):
            sid_a = student_ids[i]
            sid_b = student_ids[j]
            ma = models[sid_a]
            mb = models[sid_b]

            seat_dist = math.hypot(ma['cx'] - mb['cx'], ma['cy'] - mb['cy'])
            combined_radius = (ma['radius'] + mb['radius']) / 2
            overlap_ratio = 1 - (seat_dist / combined_radius) if combined_radius > 0 else 0

            if overlap_ratio > 0.5 and seat_dist < 150:
                overlaps.append({
                    'student_a': sid_a,
                    'student_b': sid_b,
                    'seat_dist': round(seat_dist, 1),
                    'seat_a_cx': round(ma['cx'], 1),
                    'seat_a_cy': round(ma['cy'], 1),
                    'seat_b_cx': round(mb['cx'], 1),
                    'seat_b_cy': round(mb['cy'], 1),
                    'overlap_ratio': round(overlap_ratio, 2),
                    'face_count_a': ma['face_count'],
                    'face_count_b': mb['face_count'],
                })

    overlaps.sort(key=lambda x: -x['overlap_ratio'])
    log.info("  发现 %d 个座位重叠的学生对", len(overlaps))
    return overlaps


def export_csv(filename, data, fieldnames):
    """导出 list[dict] 到 CSV"""
    path = os.path.join(OUTPUT_DIR, filename)
    with open(path, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, extrasaction='ignore')
        writer.writeheader()
        writer.writerows(data)
    log.info("  导出 %s: %d 行", filename, len(data))
    return path


def export_summary(outliers, suggestions, overlaps, models):
    """导出汇总 JSON"""
    # 座位分布统计
    seat_summary = []
    for sid, model in sorted(models.items(), key=lambda x: -x[1]['face_count']):
        seat_summary.append({
            'student_id': sid,
            'face_count': model['face_count'],
            'seat_cx': round(model['cx'], 1),
            'seat_cy': round(model['cy'], 1),
            'std_cx': round(model['std_cx'], 1),
            'std_cy': round(model['std_cy'], 1),
            'radius': round(model['radius'], 1),
            'periods': list(model['period_models'].keys()),
        })

    summary = {
        'generated_at': datetime.now().isoformat(),
        'config': {
            'spatial_threshold': SPATIAL_THRESHOLD,
            'outlier_z_score': OUTLIER_Z_SCORE,
            'min_faces_for_model': MIN_FACES_FOR_MODEL,
        },
        'students_with_model': len(models),
        'total_outliers': len(outliers),
        'total_suggestions': len(suggestions),
        'total_overlap_pairs': len(overlaps),
        'top_outliers': outliers[:20],
        'top_suggestions': suggestions[:20],
        'top_overlaps': overlaps[:10],
        'student_seat_summary': seat_summary,
    }

    path = os.path.join(OUTPUT_DIR, 'report_summary.json')
    with open(path, 'w') as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)
    log.info("  导出 report_summary.json")
    return path


def run_seat_distribution_sql(cur):
    """Step 0: 运行原始 SQL 分析，输出到 stdout"""
    log.info("=" * 60)
    log.info("SQL 座位分布分析")
    log.info("=" * 60)

    # 每个学生的座位统计
    rows = query_all(cur, """
        SELECT fr.student_id, s.name, s.student_no,
               AVG(((fr.bbox::json->>'x')::numeric + (fr.bbox::json->>'width')::numeric / 2.0)) AS avg_cx,
               AVG(((fr.bbox::json->>'y')::numeric + (fr.bbox::json->>'height')::numeric / 2.0)) AS avg_cy,
               STDDEV(((fr.bbox::json->>'x')::numeric + (fr.bbox::json->>'width')::numeric / 2.0)) AS std_cx,
               STDDEV(((fr.bbox::json->>'y')::numeric + (fr.bbox::json->>'height')::numeric / 2.0)) AS std_cy,
               COUNT(*) AS face_count,
               MIN(ci.capture_time)::text AS first_seen,
               MAX(ci.capture_time)::text AS last_seen
        FROM face_record fr
        JOIN student s ON fr.student_id = s.id
        JOIN class_image ci ON fr.class_image_id = ci.id
        WHERE fr.student_id IS NOT NULL
          AND fr.bbox IS NOT NULL AND fr.bbox != ''
        GROUP BY fr.student_id, s.name, s.student_no
        ORDER BY COUNT(*) DESC
    """)

    seat_map_rows = []
    for r in rows:
        seat_map_rows.append({
            'student_id': r[0], 'name': r[1], 'student_no': r[2],
            'avg_cx': round(r[3], 1) if r[3] else '',
            'avg_cy': round(r[4], 1) if r[4] else '',
            'std_cx': round(r[5], 1) if r[5] else '',
            'std_cy': round(r[6], 1) if r[6] else '',
            'face_count': r[7],
            'first_seen': r[8], 'last_seen': r[9],
        })

    export_csv('report_student_seat_map.csv', seat_map_rows, [
        'student_id', 'name', 'student_no', 'avg_cx', 'avg_cy',
        'std_cx', 'std_cy', 'face_count', 'first_seen', 'last_seen'
    ])

    # 打印概览
    log.info("学生座位分布 Top 10:")
    log.info("  %-12s %-8s %-8s %-8s %-8s %-8s" % (
        "student_id", "name", "faces", "avg_cx", "avg_cy", "radius"))
    for r in seat_map_rows[:10]:
        rad = max(float(r['std_cx']) if r['std_cx'] else 0,
                  float(r['std_cy']) if r['std_cy'] else 0) * 2
        log.info("  %-12s %-8s %-8s %-8s %-8s %-8s" % (
            f"#{r['student_id']}", r['name'], r['face_count'],
            r['avg_cx'], r['avg_cy'], round(rad, 1)))


def main():
    global SPATIAL_THRESHOLD, OUTLIER_Z_SCORE
    parser = argparse.ArgumentParser(description='空间位置辅助人脸匹配校准')
    parser.add_argument('--threshold', type=int, default=200,
                        help='空间匹配阈值 (px, 默认 200)')
    parser.add_argument('--z-score', type=float, default=2.5,
                        help='异常检测 z-score (默认 2.5)')
    parser.add_argument('--sql-only', action='store_true',
                        help='仅运行 SQL 分析，不执行检测')
    args = parser.parse_args()

    SPATIAL_THRESHOLD = args.threshold
    OUTLIER_Z_SCORE = args.z_score

    conn = db_connect()
    cur = conn.cursor()

    try:
        # Step 0: SQL 分析
        run_seat_distribution_sql(cur)

        if args.sql_only:
            log.info("SQL 分析完成 (--sql-only)")
            return

        # Step 1: 构建座位模型
        models = build_seat_models(cur)
        log.info("座位模型范围: cx=[%d..%d], cy=[%d..%d]",
                 min(m['cx'] for m in models.values()),
                 max(m['cx'] for m in models.values()),
                 min(m['cy'] for m in models.values()),
                 max(m['cy'] for m in models.values()))

        # Step 2: 异常匹配检测
        outliers = detect_outliers(cur, models)

        # Step 3: 建议关联
        suggestions = suggest_links(cur, models)

        # Step 4: 座位重叠检测
        overlaps = detect_seat_overlap(models)

        # 导出
        export_csv('report_spatial_outliers.csv', outliers, [
            'face_record_id', 'student_id', 'dist', 'radius', 'ratio',
            'period_ratio', 'cx', 'cy', 'seat_cx', 'seat_cy',
            'period', 'capture_time'
        ])
        export_csv('report_missed_links.csv', suggestions, [
            'face_record_id', 'student_id', 'dist', 'threshold',
            'confidence', 'cx', 'cy', 'period', 'capture_time'
        ])
        export_csv('report_seat_overlap.csv', overlaps, [
            'student_a', 'student_b', 'seat_dist',
            'seat_a_cx', 'seat_a_cy', 'seat_b_cx', 'seat_b_cy',
            'overlap_ratio', 'face_count_a', 'face_count_b'
        ])
        export_summary(outliers, suggestions, overlaps, models)

        log.info("=" * 60)
        log.info("校准完成!")
        log.info("  座位模型: %d 个学生", len(models))
        log.info("  异常匹配: %d 条", len(outliers))
        log.info("  建议关联: %d 条", len(suggestions))
        log.info("  座位重叠: %d 对", len(overlaps))
        log.info("=" * 60)
        log.info("输出文件:")
        log.info("  %s/report_student_seat_map.csv", OUTPUT_DIR)
        log.info("  %s/report_spatial_outliers.csv", OUTPUT_DIR)
        log.info("  %s/report_missed_links.csv", OUTPUT_DIR)
        log.info("  %s/report_seat_overlap.csv", OUTPUT_DIR)
        log.info("  %s/report_summary.json", OUTPUT_DIR)

    finally:
        cur.close()
        conn.close()


if __name__ == '__main__':
    main()
