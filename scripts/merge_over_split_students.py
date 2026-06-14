#!/usr/bin/env python3
"""
学生过度拆分合并脚本
==================
当前 2085 个学生，每个只有 ~24 张人脸（一个教室 ~50 人，每人大约 1200 张）。
原因是特征匹配阈值 0.55 过高，同一人的不同角度人脸被判定为不同 person。

策略：
  1. 按当前 student_id 分组，计算每个学生的平均特征向量（centroid）
  2. 对所有学生 centroid 做团合并：余弦相似度 > 阈值则视为同一人
  3. 执行合并：face_record.student_id → target student
  4. 删除被合并的空学生

用法:
  python3 scripts/merge_over_split_students.py --threshold 0.40
  python3 scripts/merge_over_split_students.py --dry-run --threshold 0.40
"""

import os, sys, json, math, base64
import argparse
import logging
from collections import defaultdict

import numpy as np
import psycopg2

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('merge_students')

DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')
PERSONS_FILE = '/tmp/pipeline_persons.json'


def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)


# ============================================================
#  Union-Find 并查集
# ============================================================
class UnionFind:
    def __init__(self, n):
        self.parent = list(range(n))
        self.rank = [0] * n

    def find(self, x):
        while self.parent[x] != x:
            self.parent[x] = self.parent[self.parent[x]]
            x = self.parent[x]
        return x

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra == rb:
            return False
        if self.rank[ra] < self.rank[rb]:
            ra, rb = rb, ra
        self.parent[rb] = ra
        if self.rank[ra] == self.rank[rb]:
            self.rank[ra] += 1
        return True


def main():
    parser = argparse.ArgumentParser(description='合并过度拆分的学生')
    parser.add_argument('--threshold', type=float, default=0.40,
                        help='余弦相似度阈值（默认 0.40，越低合并越多）')
    parser.add_argument('--dry-run', action='store_true', help='干跑，不写入 DB')
    parser.add_argument('--min-faces', type=int, default=3,
                        help='参与合并的学生最少人脸数（默认 3）')
    args = parser.parse_args()

    conn = db_connect()
    cur = conn.cursor()

    # 获取每个学生的 face_encoding 并计算 centroid
    log.info("加载学生特征向量...")
    cur.execute("""
        SELECT fr.student_id, fr.face_encoding
        FROM face_record fr
        WHERE fr.student_id IS NOT NULL
          AND fr.face_encoding IS NOT NULL AND fr.face_encoding != ''
    """)
    rows = cur.fetchall()
    log.info("  %d 条 face_record", len(rows))

    # 按 student_id 分组聚合特征向量
    student_features = defaultdict(list)
    student_names = {}
    for sid, enc_b64 in rows:
        try:
            raw = base64.b64decode(enc_b64)
            vec = np.frombuffer(raw, dtype=np.float32)
            if len(vec) == 512:
                student_features[sid].append(vec)
        except:
            pass

    # 获取学生名字
    cur.execute("SELECT id, name, student_no FROM student")
    for sid, name, sno in cur.fetchall():
        student_names[sid] = f'{name}({sno})'

    # 过滤掉人脸数太少的学生
    valid_students = {sid: vecs for sid, vecs in student_features.items()
                      if len(vecs) >= args.min_faces}
    log.info("  有效学生（>= %d faces）: %d / %d",
             args.min_faces, len(valid_students), len(student_features))

    # 计算每个学生的 centroid（平均特征向量）
    student_ids = sorted(valid_students.keys())
    n = len(student_ids)
    centroids = np.zeros((n, 512), dtype=np.float32)
    face_counts = []
    for i, sid in enumerate(student_ids):
        vecs = np.array(valid_students[sid], dtype=np.float32)
        centroids[i] = vecs.mean(axis=0)
        face_counts.append(len(vecs))

    # L2 归一化
    norms = np.linalg.norm(centroids, axis=1, keepdims=True)
    norms[norms < 1e-10] = 1
    normed = centroids / norms

    log.info("  Centroid 矩阵: %d × 512", n)

    # 团合并：用 cosine 相似度矩阵 + 并查集
    log.info("聚类中 (threshold=%s)...", args.threshold)
    uf = UnionFind(n)

    # 分块计算相似度避免 O(n²) 内存
    BLOCK = 500
    pairs_found = 0
    for start in range(0, n, BLOCK):
        end = min(start + BLOCK, n)
        block = normed[start:end]
        sims = np.dot(block, normed.T)
        for i in range(end - start):
            for j in range(n):
                if start + i < j and sims[i, j] > args.threshold:
                    if uf.union(start + i, j):
                        pairs_found += 1
        log.info("  块 %d-%d: 已找到 %d 对", start, end, pairs_found)

    # 收集聚类结果
    cluster_map = defaultdict(list)
    for i, sid in enumerate(student_ids):
        root = uf.find(i)
        cluster_map[root].append(sid)

    clusters = list(cluster_map.values())
    log.info("  合并前: %d 个学生", n)
    log.info("  合并后: %d 个簇", len(clusters))

    # 统计每个簇中最大的学生（保留的学生）
    merges = []
    total_merged = 0
    for cluster in clusters:
        if len(cluster) <= 1:
            continue
        # 保留 face_count 最多的学生
        cluster_with_counts = [(sid, face_counts[student_ids.index(sid)])
                               for sid in cluster]
        cluster_with_counts.sort(key=lambda x: -x[1])
        keep_sid, _ = cluster_with_counts[0]
        merge_from = [sid for sid, _ in cluster_with_counts[1:]]
        merges.append((keep_sid, merge_from, cluster))
        total_merged += len(merge_from)

    # 统计合并后的预计学生数
    remaining = n - total_merged
    log.info("  预计保留: %d 个学生（合并掉 %d 个）", remaining, total_merged)

    # 打印前 20 个合并
    log.info("=" * 60)
    log.info("Top 20 合并:")
    for keep_sid, merge_from, cluster in merges[:20]:
        keep_name = student_names.get(keep_sid, str(keep_sid))
        from_names = [f"#{s}({student_names.get(s, '?')})" for s in merge_from[:3]]
        fc_keep = face_counts[student_ids.index(keep_sid)]
        log.info("  保留 #%s (%s, %df) ← %s",
                 keep_sid, keep_name, fc_keep,
                 ', '.join(from_names) + (f' +{len(merge_from)-3}' if len(merge_from) > 3 else ''))

    if args.dry_run:
        log.info("=" * 60)
        log.info("干跑完成! 如需执行，去掉 --dry-run")
        log.info("  合并: %d 个簇, %d 个学生将被合并",
                 len(merges), total_merged)
        cur.close()
        conn.close()
        return

    # 执行合并
    log.info("=" * 60)
    log.info("执行合并...")

    merged_count = 0
    face_moved = 0
    for keep_sid, merge_from, cluster in merges:
        for old_sid in merge_from:
            # 把 face_record 移到保留学生
            cur.execute("UPDATE face_record SET student_id = %s WHERE student_id = %s",
                       (keep_sid, old_sid))
            moved = cur.rowcount
            face_moved += moved
            # 删除空学生
            cur.execute("DELETE FROM student WHERE id = %s", (old_sid,))
            merged_count += 1

        if merged_count % 100 == 0:
            conn.commit()
            log.info("  Progress: %d/%d students merged", merged_count, total_merged)

    conn.commit()

    log.info("=" * 60)
    log.info("合并完成!")
    log.info("  合并: %d 个学生 → %d 个簇", total_merged, remaining)
    log.info("  移动: %d 条 face_record", face_moved)

    # 最终统计
    cur.execute("SELECT COUNT(*) FROM student")
    final_students = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM face_record WHERE student_id IS NOT NULL")
    linked = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM face_record")
    total_fr = cur.fetchone()[0]
    log.info("  最终: %d 学生, %d/%d face_record 已关联",
             final_students, linked, total_fr)

    # 更新 person registry
    if os.path.exists(PERSONS_FILE):
        try:
            with open(PERSONS_FILE) as f:
                registry = json.load(f)
            persons = registry.get('persons', {})
            # 对每个合并，更新 person 的 student_id
            for keep_sid, merge_from, cluster in merges:
                all_sids = [keep_sid] + merge_from
                for pid, info in persons.items():
                    if info.get('student_id') in all_sids:
                        info['student_id'] = keep_sid
            with open(PERSONS_FILE, 'w') as f:
                json.dump(registry, f, indent=2)
            log.info("  person registry 已更新")
        except Exception as e:
            log.warning("  更新 person registry 失败: %s", e)

    cur.close()
    conn.close()


if __name__ == '__main__':
    main()
