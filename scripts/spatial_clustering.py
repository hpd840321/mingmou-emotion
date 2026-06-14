#!/usr/bin/env python3
"""
空间-时间聚类脚本
================
基于 face_record 的 bbox 空间位置 + 节次信息，将属于同一座位的人脸聚类到一起。

流程:
  1. 按 period 分组，在单 period 内用 DBSCAN 聚类（同节次内座位稳定）
  2. 跨 period 簇匹配：贪心配对（每个簇只匹配一次，防链式合并）
  3. 创建学生记录并关联 face_record

用法:
  python3 scripts/spatial_clustering.py --eps 120
  python3 scripts/spatial_clustering.py --dry-run --eps 120
"""

import os, sys, json, math
import argparse
import logging
from collections import defaultdict

import numpy as np
import psycopg2
from sklearn.cluster import DBSCAN

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('spatial_cluster')

DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')
PERSONS_FILE = '/tmp/pipeline_persons.json'


def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)


def main():
    parser = argparse.ArgumentParser(description='空间-时间聚类')
    parser.add_argument('--eps', type=int, default=120,
                        help='DBSCAN 空间距离阈值 (px, 默认 120)')
    parser.add_argument('--min-samples', type=int, default=3,
                        help='DBSCAN 最小样本数 (默认 3)')
    parser.add_argument('--match-dist', type=int, default=120,
                        help='跨 period 匹配距离 (px, 默认 120)')
    parser.add_argument('--exclude-movement', action='store_true', default=True,
                        help='排除非上课时段（午餐、课间等）')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    conn = db_connect()
    cur = conn.cursor()

    # 1. 加载数据
    log.info("Step 1: 加载 face_record bbox...")
    cur.execute("""
        SELECT fr.id,
               (fr.bbox::json->>'x')::numeric + (fr.bbox::json->>'width')::numeric / 2 AS cx,
               (fr.bbox::json->>'y')::numeric + (fr.bbox::json->>'height')::numeric / 2 AS cy,
               COALESCE(ci.period_label, 'other') AS period
        FROM face_record fr
        JOIN class_image ci ON fr.class_image_id = ci.id
        WHERE fr.bbox IS NOT NULL AND fr.bbox != ''
    """)
    rows = cur.fetchall()
    log.info("  %d 条 face_record", len(rows))

    # 2. 按 period 分组聚类
    log.info("Step 2: 按 period 聚类...")

    # 只使用二期中文名节次（最新管线，检测质量高）
    # 排除非上课时段 + 一期英文名节次
    EXCLUDED = {'lunch', 'afterclass', 'recess', 'arrival', 'period_1', 'period_2',
                'period_3', 'period_4', 'period_5', 'period_6', 'period_7', 'period_8',
                '午餐-午休', '课外活动-放学', '课间操', '早读-到校'}
    filtered_rows = [(fid, cx, cy, p) for fid, cx, cy, p in rows
                     if p not in EXCLUDED]
    log.info("  排除 %d 个标签: %s", len(EXCLUDED), EXCLUDED)
    log.info("  剩余 %d 条记录", len(filtered_rows))

    by_period = defaultdict(list)
    for fid, cx, cy, period in filtered_rows:
        by_period[period].append((fid, float(cx), float(cy)))

    # (period, cluster_label) -> {'fr_ids': [...], 'centroid': (cx,cy), 'faces': [...]}
    cluster_faces = defaultdict(list)

    for period, faces in sorted(by_period.items()):
        if len(faces) < args.min_samples:
            for fid, cx, cy in faces:
                cluster_faces[(period, -1)].append(fid)
            continue

        X = np.array([(cx, cy) for _, cx, cy in faces])
        ids = [fid for fid, _, _ in faces]

        db = DBSCAN(eps=args.eps, min_samples=args.min_samples,
                    metric='euclidean', n_jobs=-1)
        labels = db.fit_predict(X)

        n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
        log.info("  %-20s: %4d faces → %2d clusters", period, len(faces), n_clusters)

        for i, label in enumerate(labels):
            cluster_faces[(period, label)].append(ids[i])

    # Filter out noise
    valid_clusters = {(p, l): v for (p, l), v in cluster_faces.items()
                      if l >= 0 and len(v) >= args.min_samples}

    # Compute centroids
    id_to_xy = {fid: (cx, cy) for fid, cx, cy, _ in rows}
    cluster_info = {}  # (period, label) -> {'fr_ids': [...], 'centroid': (cx,cy)}
    for k, fr_ids in valid_clusters.items():
        pts = [id_to_xy[fid] for fid in fr_ids]
        centroid = np.mean(pts, axis=0)
        cluster_info[k] = {'fr_ids': fr_ids, 'centroid': tuple(centroid)}

    log.info("  总计 %d 个簇 (跨 %d periods)", len(cluster_info), len(by_period))

    # 3. 跨 period 匹配（贪心，每个簇只匹配一次）
    log.info("Step 3: 跨 period 匹配 (dist=%dpx)...", args.match_dist)

    # Sort clusters by size descending (larger clusters matched first)
    sorted_clusters = sorted(cluster_info.items(), key=lambda x: -len(x[1]['fr_ids']))
    student_clusters = []  # [(centroid, [fr_ids])]
    matched = set()

    for (period, label), info in sorted_clusters:
        centroid = info['centroid']
        fr_ids = info['fr_ids']

        if (period, label) in matched:
            continue

        # Find existing student cluster with closest centroid
        best_idx = -1
        best_dist = args.match_dist
        for si, (sc, sfr) in enumerate(student_clusters):
            dist = math.hypot(centroid[0] - sc[0], centroid[1] - sc[1])
            if dist < best_dist:
                best_dist = dist
                best_idx = si

        if best_idx >= 0:
            student_clusters[best_idx] = (
                student_clusters[best_idx][0],  # keep original centroid
                student_clusters[best_idx][1] + fr_ids
            )
        else:
            student_clusters.append((centroid, list(fr_ids)))

        matched.add((period, label))

    log.info("  合并结果: %d 个学生", len(student_clusters))

    # Statistics
    sizes = [len(sfr) for _, sfr in student_clusters]
    log.info("  min=%d, max=%d, avg=%.0f, median=%d",
             min(sizes), max(sizes), np.mean(sizes), int(np.median(sizes)))
    log.info("  noise (未分类): %d",
             sum(len(v) for (p, l), v in cluster_faces.items() if l < 0))

    # Show top 10
    student_clusters.sort(key=lambda x: -len(x[1]))
    for i, (centroid, fr_ids) in enumerate(student_clusters[:10]):
        log.info("  学生 %d: %5d faces, center=(%.0f, %.0f)",
                 i + 1, len(fr_ids), centroid[0], centroid[1])

    if args.dry_run:
        log.info("\n干跑完成，预计创建 %d 个学生", len(student_clusters))
        cur.close()
        conn.close()
        return

    # 4. 创建学生并关联 face_record
    log.info("Step 4: 创建学生记录...")

    # Get next student_id
    cur.execute("SELECT COALESCE(MAX(id), 0) + 1 FROM student")
    next_id = cur.fetchone()[0]
    log.info("  起始 student_id: %d", next_id)

    # Create students and link face_records
    created = 0
    linked = 0
    for centroid, fr_ids in student_clusters:
        # Create student
        student_no = f'stu{next_id:04d}'
        student_name = f'座位{next_id:03d}'
        cur.execute("""INSERT INTO student (id, name, student_no, status, class_id)
                       VALUES (%s, %s, %s, 'active', 1)""",
                    (next_id, student_name, student_no))

        # Link face_records
        for fr_id in fr_ids:
            cur.execute("UPDATE face_record SET student_id = %s WHERE id = %s",
                       (next_id, fr_id))
            linked += 1

        created += 1
        next_id += 1

        if created % 50 == 0:
            conn.commit()
            log.info("  Progress: %d students, %d linked", created, linked)

    conn.commit()

    # Fix student_id sequence
    cur.execute("SELECT setval('student_id_seq', GREATEST(nextval('student_id_seq'), %s))",
                (next_id - 1,))

    log.info("  创建 %d 个学生", created)
    log.info("  关联 %d 条 face_record", linked)

    # 5. Verify
    cur.execute("SELECT COUNT(*) FROM student")
    log.info("  当前学生总数: %d", cur.fetchone()[0])
    cur.execute("SELECT COUNT(*) FROM face_record WHERE student_id IS NOT NULL")
    log.info("  已关联 face_record: %d", cur.fetchone()[0])

    cur.close()
    conn.close()


if __name__ == '__main__':
    main()
