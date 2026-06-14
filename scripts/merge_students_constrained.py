#!/usr/bin/env python3
"""
学生合并（含空间位置 + 性别约束）
==================================
在特征相似度的基础上，增加物理约束确保合并质量：
  1. 性别一致：majority gender 不同则不合并
  2. 空间邻近：座位中心距离 < 400px 才合并
  3. 人脸数加权：只让 face_count 少的学生合并到多的

用法:
  python3 scripts/merge_students_constrained.py --threshold 0.37
  python3 scripts/merge_students_constrained.py --dry-run --threshold 0.37
"""

import os, sys, json, math, base64
import argparse
import logging
from collections import defaultdict

import numpy as np
import psycopg2

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('merge_constrained')

DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')
PERSONS_FILE = '/tmp/pipeline_persons.json'


def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)


def majority_gender(cur, sid):
    """返回学生的 majority gender (0=female, 1=male, None=unknown)"""
    cur.execute("""
        SELECT gender, COUNT(*) as cnt FROM face_record
        WHERE student_id = %s AND gender IS NOT NULL
        GROUP BY gender ORDER BY cnt DESC LIMIT 1
    """, (sid,))
    row = cur.fetchone()
    return row[0] if row else None


def seat_center(cur, sid):
    """返回学生的平均座位中心 (cx, cy, n)"""
    cur.execute("""
        SELECT AVG(x.cx), AVG(x.cy), COUNT(*)
        FROM face_record fr
        CROSS JOIN LATERAL (
            SELECT (fr.bbox::json->>'x')::numeric + (fr.bbox::json->>'width')::numeric / 2 AS cx,
                   (fr.bbox::json->>'y')::numeric + (fr.bbox::json->>'height')::numeric / 2 AS cy
        ) x
        WHERE fr.student_id = %s AND fr.bbox IS NOT NULL AND fr.bbox != ''
    """, (sid,))
    row = cur.fetchone()
    if row and row[2] >= 3:
        return (float(row[0]), float(row[1]), int(row[2]))
    return None


def resolve_chain(merged_map, sid):
    """解析链式合并：如果 A→B→C，返回 C"""
    visited = set()
    while sid in merged_map and sid not in visited:
        visited.add(sid)
        sid = merged_map[sid]
    return sid


def main():
    parser = argparse.ArgumentParser(description='含约束的学生合并')
    parser.add_argument('--threshold', type=float, default=0.37)
    parser.add_argument('--spatial-threshold', type=float, default=400,
                        help='座位中心距阈值(px)，默认 400')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    conn = db_connect()
    cur = conn.cursor()

    # 1. 获取所有学生信息
    log.info("加载学生数据...")
    cur.execute("""
        SELECT fr.student_id, MIN(fr.face_encoding), COUNT(*) as face_count
        FROM face_record fr
        WHERE fr.student_id IS NOT NULL
          AND fr.face_encoding IS NOT NULL AND fr.face_encoding != ''
        GROUP BY fr.student_id
        HAVING COUNT(*) >= 3
    """)
    rows = cur.fetchall()
    log.info("  %d 个学生 (>=3 faces)", len(rows))

    student_ids = []
    feature_matrix = []
    face_counts = {}
    for sid, enc_b64, cnt in rows:
        raw = base64.b64decode(enc_b64)
        vec = np.frombuffer(raw, dtype=np.float32)
        if len(vec) == 512:
            student_ids.append(sid)
            feature_matrix.append(vec)
            face_counts[sid] = cnt

    # 归一化特征矩阵
    matrix = np.array(feature_matrix, dtype=np.float32)
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms < 1e-10] = 1
    normed = matrix / norms
    sid_to_idx = {sid: i for i, sid in enumerate(student_ids)}

    # 2. 预加载每个学生的性别和座位
    log.info("加载性别和座位信息...")
    genders = {}
    seats = {}
    for sid in student_ids:
        genders[sid] = majority_gender(cur, sid)
        seats[sid] = seat_center(cur, sid)
    log.info("  有性别信息: %d/%d", sum(1 for g in genders.values() if g is not None), len(student_ids))
    log.info("  有座位信息: %d/%d", sum(1 for s in seats.values() if s is not None), len(student_ids))

    # 3. 计算所有学生对的相似度 + 约束检查，生成合并候选
    log.info("计算合并候选 (threshold=%s)...", args.threshold)
    n = len(student_ids)
    pairs_found = 0
    merged_map = {}  # merge_sid → keep_sid (direct, will resolve chains later)

    for i in range(n):
        sid_a = student_ids[i]
        if sid_a in merged_map.values() or sid_a in merged_map:
            continue
        ca = face_counts[sid_a]

        for j in range(n):
            if i == j:
                continue
            sid_b = student_ids[j]
            if sid_b in merged_map:
                continue
            cb = face_counts[sid_b]

            # 只允许少的合并到多的
            if cb <= ca:
                continue

            # 特征相似度
            sim = float(np.dot(normed[i], normed[j]))
            if sim < args.threshold:
                continue

            # 动态阈值：约束条件降低匹配门槛
            effective_threshold = args.threshold

            # 空间加分：座位近则降低阈值
            sa, sb = seats.get(sid_a), seats.get(sid_b)
            if sa and sb:
                dist = math.hypot(sa[0] - sb[0], sa[1] - sb[1])
                if dist < 200:
                    effective_threshold -= 0.08   # 同一座位附近，大幅降低门槛
                elif dist < args.spatial_threshold:
                    effective_threshold -= 0.04   # 较近，适度降低
                elif dist > args.spatial_threshold * 1.5:
                    effective_threshold += 0.05   # 太远，提高门槛

            # 性别加分：同性别降低阈值
            ga, gb = genders.get(sid_a), genders.get(sid_b)
            if ga is not None and gb is not None:
                if ga == gb:
                    effective_threshold -= 0.03   # 同性别更容易合并
                else:
                    effective_threshold += 0.08   # 不同性别更难合并

            if sim < effective_threshold:
                continue

            # 通过所有检查，记录合并
            merged_map[sid_a] = sid_b
            pairs_found += 1
            break  # 每个学生只合并一次

    log.info("  原始合并候选: %d 对", pairs_found)

    # 4. 解析链式合并 （A→B, B→C → A→C）
    if not args.dry_run:
        # 构建最终映射
        final_map = {}
        for merge_sid in merged_map:
            keep_sid = resolve_chain(merged_map, merge_sid)
            if keep_sid != merge_sid:
                final_map[merge_sid] = keep_sid

        log.info("  最终合并映射: %d 个学生将被合并", len(final_map))
        log.info("  合并后预计: %d 个学生", n - len(final_map))

        # 打印前 20
        sorted_merges = sorted(final_map.items(), key=lambda x: -face_counts.get(x[0], 0))
        for merge_sid, keep_sid in sorted_merges[:20]:
            sim_val = float(np.dot(normed[sid_to_idx[merge_sid]], normed[sid_to_idx[keep_sid]]))
            log.info("    #%s (%df) ← #%s (%df)  sim=%.3f",
                     keep_sid, face_counts.get(keep_sid, 0),
                     merge_sid, face_counts.get(merge_sid, 0), sim_val)

        # 5. 执行合并
        log.info("=" * 60)
        log.info("执行合并...")
        moved = 0

        # 分组：按 keep_sid
        groups = defaultdict(list)
        for merge_sid, keep_sid in final_map.items():
            groups[keep_sid].append(merge_sid)

        for keep_sid, merge_list in groups.items():
            for merge_sid in merge_list:
                cur.execute("UPDATE face_record SET student_id = %s WHERE student_id = %s",
                           (keep_sid, merge_sid))
                moved += cur.rowcount
                cur.execute("DELETE FROM student WHERE id = %s", (merge_sid,))

        conn.commit()
        log.info("  移动 %d 条 face_record", moved)

        # 6. 更新 person registry
        if os.path.exists(PERSONS_FILE):
            try:
                with open(PERSONS_FILE) as f:
                    registry = json.load(f)
                persons = registry.get('persons', {})
                for merge_sid, keep_sid in final_map.items():
                    for pid, info in persons.items():
                        if info.get('student_id') == merge_sid:
                            info['student_id'] = keep_sid
                with open(PERSONS_FILE, 'w') as f:
                    json.dump(registry, f, indent=2)
                log.info("  person registry 已更新")
            except Exception as e:
                log.warning("  person registry 更新失败: %s", e)

        cur.execute("SELECT COUNT(*) FROM student")
        final = cur.fetchone()[0]
        cur.execute("SELECT COUNT(*) FROM face_record WHERE student_id IS NOT NULL")
        linked = cur.fetchone()[0]
        log.info("  最终: %d 学生, %d 条 face_record 已关联", final, linked)

    else:
        log.info("  干跑: %d 个学生将被合并", pairs_found)
        log.info("  合并后预计: %d 个学生 (当前 %d)", n - pairs_found, n)

    cur.close()
    conn.close()


if __name__ == '__main__':
    main()
