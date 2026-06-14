#!/usr/bin/env python3
"""
学生贪心合并（无链式传递）
======================
每个学生最多只能被合并一次，只与最相似的学生配对合并。
避免链式传递导致的"一人吞全部"问题。

用法:
  python3 scripts/merge_students_simple.py --threshold 0.55
  python3 scripts/merge_students_simple.py --dry-run --threshold 0.55
"""

import os, sys, json, base64
import argparse
import logging

import numpy as np
import psycopg2

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('merge_simple')

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
    parser = argparse.ArgumentParser(description='贪心合并学生（无链式传递）')
    parser.add_argument('--threshold', type=float, default=0.55,
                        help='余弦相似度阈值')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    conn = db_connect()
    cur = conn.cursor()

    log.info("加载学生特征向量...")
    cur.execute("""
        SELECT fr.student_id, MIN(fr.face_encoding)
        FROM face_record fr
        WHERE fr.student_id IS NOT NULL
          AND fr.face_encoding IS NOT NULL AND fr.face_encoding != ''
        GROUP BY fr.student_id
        HAVING COUNT(*) >= 3
    """)
    rows = cur.fetchall()
    log.info("  %d 个学生", len(rows))

    student_ids = []
    matrix = []
    for sid, enc_b64 in rows:
        raw = base64.b64decode(enc_b64)
        vec = np.frombuffer(raw, dtype=np.float32)
        if len(vec) == 512:
            student_ids.append(sid)
            matrix.append(vec)

    matrix = np.array(matrix, dtype=np.float32)
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms < 1e-10] = 1
    normed = matrix / norms

    # 计算每个人脸数，保留多的学生
    cur.execute("SELECT student_id, COUNT(*) FROM face_record WHERE student_id IS NOT NULL GROUP BY student_id")
    face_counts = dict(cur.fetchall())

    n = len(student_ids)
    log.info("  计算 %d × %d 相似度矩阵...", n, n)

    # 相似度矩阵
    sims = np.dot(normed, normed.T)

    # 贪心配对：每个学生 i，找 j (face_count[j] >= face_count[i]) 中相似度最高的
    # i 只能被合并一次
    log.info("  贪心配对中 (threshold=%s)...", args.threshold)

    merged_from = set()
    merges = []  # [(keep_sid, merge_sid), ...]

    # 按 face_count 从少到多排序（少的合并到多的）
    sorted_indices = sorted(range(n), key=lambda i: face_counts.get(student_ids[i], 0))

    for idx in sorted_indices:
        sid = student_ids[idx]
        if sid in merged_from:
            continue

        # 找 face_count >= 当前且相似度最高的
        best_j = -1
        best_sim = 0
        for j in range(n):
            if j == idx:
                continue
            jsid = student_ids[j]
            if jsid in merged_from:
                continue
            if face_counts.get(jsid, 0) < face_counts.get(sid, 0):
                continue  # 只合并到人脸更多的学生
            if sims[idx, j] > best_sim:
                best_sim = sims[idx, j]
                best_j = j

        if best_j >= 0 and best_sim >= args.threshold:
            keep_sid = student_ids[best_j]
            merges.append((keep_sid, sid))
            merged_from.add(sid)

    log.info("  配对结果: %d 个学生将被合并（保留 %d 个）",
             len(merges), n - len(merges))

    # 打印前 20
    merges.sort(key=lambda x: -face_counts.get(x[0], 0))
    for keep_sid, merge_sid in merges[:20]:
        log.info("    #%s (%df) ← #%s (%df)  sim=%.3f",
                 keep_sid, face_counts.get(keep_sid, 0),
                 merge_sid, face_counts.get(merge_sid, 0),
                 sims[student_ids.index(keep_sid), student_ids.index(merge_sid)])

    log.info("  合并后预计: %d 个学生", n - len(merges))

    if args.dry_run:
        return

    # 解析链式合并：构建最终合并映射
    log.info("=" * 60)
    log.info("执行合并...")

    # 构建链式映射 merge_sid → keep_sid
    merge_map = {}
    for keep_sid, merge_sid in merges:
        merge_map[merge_sid] = keep_sid

    # 解析链：A→B, B→C => A→C
    def resolve(sid, seen=None):
        if seen is None:
            seen = set()
        if sid in seen:
            return sid
        seen.add(sid)
        nxt = merge_map.get(sid)
        if nxt is None or nxt == sid:
            return sid
        return resolve(nxt, seen)

    # 最终映射（排除 self-loop）
    final_map = {}
    for merge_sid in merge_map:
        keep_sid = resolve(merge_sid)
        if keep_sid != merge_sid:
            final_map[merge_sid] = keep_sid

    log.info("  最终合并映射: %d 条", len(final_map))
    if len(final_map) != len(merges):
        log.info("  (链式解析减少: %d → %d)", len(merges), len(final_map))

    # 先全部 UPDATE，再全部 DELETE（确保 FK 安全）
    moved = 0
    for merge_sid, keep_sid in final_map.items():
        cur.execute("UPDATE face_record SET student_id = %s WHERE student_id = %s",
                   (keep_sid, merge_sid))
        moved += cur.rowcount
    conn.commit()

    for merge_sid in final_map:
        cur.execute("DELETE FROM student WHERE id = %s", (merge_sid,))
    conn.commit()

    conn.commit()
    log.info("  移动 %d 条 face_record", moved)

    # 更新 person registry
    if os.path.exists(PERSONS_FILE):
        try:
            with open(PERSONS_FILE) as f:
                registry = json.load(f)
            persons = registry.get('persons', {})
            for keep_sid, merge_sid in merges:
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

    cur.close()
    conn.close()


if __name__ == '__main__':
    main()
