#!/usr/bin/env python3
"""
全量特征提取 + 多算法聚类评估
=============================
1. 对所有源图并发提取 512-dim 特征
2. 匹配到已有 face_record（bbox IoU）
3. 运行多种聚类算法对比评估
4. 输出最优方案

用法:
  python3 scripts/feature_extract_and_cluster.py
  python3 scripts/feature_extract_and_cluster.py --extract-only   # 只提取不聚类
  python3 scripts/feature_extract_and_cluster.py --cluster-only   # 只聚类不提取
"""

import os, sys, json, struct, time, math, csv
import argparse
import logging
from collections import defaultdict

import numpy as np
import grpc
import psycopg2

sys.path.insert(0, '/tmp/proto_out')
from inference_pb2_grpc import FaceServiceStub
from inference_pb2 import FaceAnalysisRequest

logging.basicConfig(level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger('feat_cluster')

DB_HOST = os.environ.get('DB_HOST', 'localhost')
DB_PORT = int(os.environ.get('DB_PORT', 5432))
DB_NAME = os.environ.get('DB_NAME', 'emotion_platform')
DB_USER = os.environ.get('DB_USER', 'emotion')
DB_PASS = os.environ.get('DB_PASS', 'emotion')
GRPC_HOST = os.environ.get('GRPC_HOST', 'localhost:50053')
IOU_THRESHOLD = 0.25
FEAT_CACHE = '/media/zebra/data/官渡一中初一班-0526/data/backup/full_features.json'


def db_connect():
    return psycopg2.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                            password=DB_PASS, database=DB_NAME)


def bbox_iou(a, b):
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    ix = max(ax, bx); iy = max(ay, by)
    iw = min(ax+aw, bx+bw) - ix
    ih = min(ay+ah, by+bh) - iy
    if iw <= 0 or ih <= 0: return 0.0
    inter = iw * ih
    union = aw*ah + bw*bh - inter
    return inter / union if union > 0 else 0.0


def extract_features(args):
    """全量特征提取"""
    conn = db_connect()
    cur = conn.cursor()

    # 获取所有源图
    cur.execute("SELECT id, image_url FROM class_image WHERE image_url IS NOT NULL")
    all_images = cur.fetchall()
    log.info("源图总数: %d", len(all_images))

    # 预加载所有 face_record bbox（按 class_image_id 分组）
    log.info("加载已有 bbox...")
    ci_bbox = {}
    cur.execute("""
        SELECT class_image_id, id,
               (bbox::json->>'x')::numeric::int,
               (bbox::json->>'y')::numeric::int,
               (bbox::json->>'width')::numeric::int,
               (bbox::json->>'height')::numeric::int
        FROM face_record WHERE bbox IS NOT NULL
    """)
    for ci_id, fr_id, x, y, w, h in cur.fetchall():
        ci_bbox.setdefault(ci_id, []).append((fr_id, [x, y, w, h]))
    log.info("  %d 张图有 bbox 数据", len(ci_bbox))

    cur.close()
    conn.close()

    # 并发提取
    fr_features = {}
    stats = {'images': 0, 'detected': 0, 'matched': 0, 'errors': 0}

    def process_one(ci_id, img_url):
        if not os.path.exists(img_url):
            return (0, 0, 1, {})
        try:
            with open(img_url, 'rb') as f:
                data = f.read()
        except:
            return (0, 0, 1, {})

        stub = FaceServiceStub(grpc.insecure_channel(GRPC_HOST,
            options=[('grpc.max_send_message_length', 50*1024*1024),
                     ('grpc.max_receive_message_length', 50*1024*1024)]))
        try:
            req = FaceAnalysisRequest(image_data=data, enabled_features=0xB3)
            resp = stub.Analyze(req, timeout=60)
        except:
            return (0, 0, 1, {})

        if not resp.success or not resp.faces:
            return (0, 0, 0, {})

        n_detected = len(resp.faces)
        n_matched = 0
        local_feats = {}
        existing = ci_bbox.get(ci_id, [])

        for f in resp.faces:
            if f.feature_dim <= 0:
                continue
            tok = f.token
            db = [int(tok.x), int(tok.y), int(tok.width), int(tok.height)]

            best_iou = 0
            best_fr_id = None
            for fr_id, eb in existing:
                iou = bbox_iou(db, eb)
                if iou > best_iou:
                    best_iou = iou
                    best_fr_id = fr_id

            if best_iou >= IOU_THRESHOLD and best_fr_id:
                vals = struct.unpack(f'{f.feature_dim}f', f.feature)
                local_feats[best_fr_id] = [float(v) for v in vals]
                n_matched += 1

        return (n_detected, n_matched, 0, local_feats)

    import concurrent.futures
    log.info("开始特征提取 (5 workers)...")
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        fut_map = {pool.submit(process_one, ci_id, url): (ci_id, url)
                   for ci_id, url in all_images}
        for i, fut in enumerate(concurrent.futures.as_completed(fut_map)):
            det, match, err, feats = fut.result()
            stats['detected'] += det
            stats['matched'] += match
            stats['errors'] += err
            if feats:
                stats['images'] += 1
                fr_features.update(feats)

            if (i + 1) % 500 == 0:
                log.info("  Progress: %d/%d images, %d matched, %d err",
                         i + 1, len(all_images), stats['matched'], stats['errors'])

    elapsed = time.time() - stats.get('_start', time.time())
    log.info("提取完成: %d 张图, %d 人脸, %d 匹配, %d 错误, %.0fs",
             stats['images'], stats['detected'], stats['matched'], stats['errors'], elapsed)

    # 保存
    log.info("保存特征到 %s...", FEAT_CACHE)
    with open(FEAT_CACHE, 'w') as f:
        json.dump(fr_features, f)
    log.info("  保存 %d 条特征向量", len(fr_features))

    # 备份到 backup
    import shutil
    backup_path = FEAT_CACHE.replace('full_features.json', 'full_features_backup.json')
    shutil.copy(FEAT_CACHE, backup_path)

    return fr_features


def evaluate_clustering(features):
    """对特征运行多种聚类算法并评估"""
    log.info("\n" + "=" * 70)
    log.info("聚类算法评估")
    log.info("=" * 70)

    fr_ids = list(features.keys())
    X = np.array([features[fid] for fid in fr_ids], dtype=np.float32)
    # L2 归一化
    norms = np.linalg.norm(X, axis=1, keepdims=True)
    norms[norms < 1e-10] = 1
    X_norm = X / norms

    from sklearn.metrics import silhouette_score, davies_bouldin_score
    from sklearn.cluster import DBSCAN, AgglomerativeClustering, KMeans, SpectralClustering
    from sklearn.metrics.pairwise import cosine_similarity
    import hdbscan

    results = []

    # 1. DBSCAN
    log.info("\n--- DBSCAN ---")
    for eps in [0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60]:
        for min_samples in [3, 5, 10]:
            model = DBSCAN(eps=eps, min_samples=min_samples, metric='cosine', n_jobs=-1)
            labels = model.fit_predict(X_norm)
            n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
            n_noise = list(labels).count(-1)
            if n_clusters < 10 or n_clusters > 200:
                continue
            # Silhouette (exclude noise)
            mask = labels != -1
            if mask.sum() < 10 or len(set(labels[mask])) < 2:
                continue
            try:
                sil = silhouette_score(X_norm[mask], labels[mask], metric='cosine')
            except:
                sil = -1
            db = davies_bouldin_score(X_norm[mask], labels[mask])
            intra = _intra_cluster_sim(X_norm, labels)
            results.append(('DBSCAN', eps, min_samples, n_clusters, n_noise, sil, db, intra))
            log.info("  DBSCAN eps=%.2f min=%d: %d clusters, %d noise, sil=%.3f, db=%.3f, intra=%.3f",
                     eps, min_samples, n_clusters, n_noise, sil, db, intra)

    # 2. HDBSCAN
    log.info("\n--- HDBSCAN ---")
    for min_cluster in [5, 10, 15, 20, 30]:
        for min_samples in [3, 5, 10]:
            try:
                model = hdbscan.HDBSCAN(min_cluster_size=min_cluster, min_samples=min_samples,
                                         metric='euclidean', gen_min_span_tree=True)
                labels = model.fit_predict(X_norm)
                n_clusters = len(set(labels)) - (1 if -1 in labels else 0)
                n_noise = list(labels).count(-1)
                if n_clusters < 5 or n_clusters > 200:
                    continue
                mask = labels != -1
                if mask.sum() < 10 or len(set(labels[mask])) < 2:
                    continue
                try:
                    sil = silhouette_score(X_norm[mask], labels[mask], metric='cosine')
                except:
                    sil = -1
                db = davies_bouldin_score(X_norm[mask], labels[mask])
                intra = _intra_cluster_sim(X_norm, labels)
                results.append(('HDBSCAN', min_cluster, min_samples, n_clusters, n_noise, sil, db, intra))
                log.info("  HDBSCAN mc=%d ms=%d: %d clusters, %d noise, sil=%.3f, db=%.3f, intra=%.3f",
                         min_cluster, min_samples, n_clusters, n_noise, sil, db, intra)
            except Exception as e:
                log.warning("  HDBSCAN mc=%d ms=%d failed: %s", min_cluster, min_samples, e)

    # 3. Agglomerative
    log.info("\n--- Agglomerative ---")
    for n in range(30, 81, 5):
        for link in ['complete', 'average', 'ward']:
            if link == 'ward' and n > len(X_norm):
                continue
            model = AgglomerativeClustering(n_clusters=n, linkage=link, metric='cosine')
            try:
                labels = model.fit_predict(X_norm)
            except:
                continue
            if len(set(labels)) < 2:
                continue
            try:
                sil = silhouette_score(X_norm, labels, metric='cosine')
            except:
                sil = -1
            db = davies_bouldin_score(X_norm, labels)
            intra = _intra_cluster_sim(X_norm, labels)
            results.append(('Agglomerative', n, 0, len(set(labels)), 0, sil, db, intra))
            log.info("  Agglomerative n=%d %s: sil=%.3f, db=%.3f, intra=%.3f",
                     n, link, sil, db, intra)

    # 4. K-Means
    log.info("\n--- K-Means ---")
    for n in range(30, 81, 5):
        model = KMeans(n_clusters=n, random_state=42, n_init=5)
        labels = model.fit_predict(X_norm)
        try:
            sil = silhouette_score(X_norm, labels, metric='cosine')
        except:
            sil = -1
        db = davies_bouldin_score(X_norm, labels)
        intra = _intra_cluster_sim(X_norm, labels)
        results.append(('KMeans', n, 0, n, 0, sil, db, intra))
        log.info("  KMeans n=%d: sil=%.3f, db=%.3f, intra=%.3f", n, sil, db, intra)

    # 排序：按轮廓系数降序
    results.sort(key=lambda x: -x[5])
    
    log.info("\n" + "=" * 70)
    log.info("Top 10 方案:")
    log.info(f"  {'算法':<15s} {'参数':>8s} {'簇数':>5s} {'噪音':>6s} {'轮廓':>7s} {'DB':>6s} {'簇内':>6s}")
    for r in results[:10]:
        algo = r[0]
        if algo == 'DBSCAN':
            param = f"eps={r[1]:.2f}"
        elif algo == 'HDBSCAN':
            param = f"mc={r[1]:.0f}"
        else:
            param = f"n={r[1]:.0f}"
        log.info(f"  {algo:<15s} {param:>8s} {r[3]:>5d} {r[4]:>6d} {r[5]:>7.3f} {r[6]:>6.3f} {r[7]:>6.3f}")

    # 保存结果
    out_path = '/media/zebra/data/官渡一中初一班-0526/data/backup/clustering_evaluation.csv'
    with open(out_path, 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['algorithm', 'param1', 'param2', 'n_clusters', 'n_noise',
                    'silhouette', 'davies_bouldin', 'intra_sim'])
        for r in results:
            w.writerow(r)
    log.info("评估结果已保存: %s", out_path)

    return results


def _intra_cluster_sim(X, labels):
    """计算簇内平均余弦相似度"""
    sims = []
    for label in set(labels):
        if label < 0:
            continue
        mask = labels == label
        pts = X[mask]
        if len(pts) < 2:
            continue
        # 随机采样最多 50 个点计算
        if len(pts) > 50:
            idx = np.random.choice(len(pts), 50, replace=False)
            pts = pts[idx]
        sim_matrix = np.dot(pts, pts.T)
        n = len(pts)
        avg = (sim_matrix.sum() - n) / max(n * n - n, 1)
        sims.append(avg)
    return np.mean(sims) if sims else 0


def get_student_sids(cur):
    """获取 face_record 当前的 student_id"""
    cur.execute("SELECT id, student_id FROM face_record WHERE student_id IS NOT NULL")
    return {r[0]: r[1] for r in cur.fetchall()}


def main():
    parser = argparse.ArgumentParser(description='全量特征提取+聚类评估')
    parser.add_argument('--extract-only', action='store_true')
    parser.add_argument('--cluster-only', action='store_true')
    parser.add_argument('--recluster', action='store_true', help='按最优方案重新分配学生')
    args = parser.parse_args()

    features = {}

    if not args.cluster_only:
        features = extract_features(args)
    else:
        if os.path.exists(FEAT_CACHE):
            with open(FEAT_CACHE) as f:
                features = json.load(f)
            log.info("加载 %d 条缓存特征", len(features))
        else:
            log.error("特征缓存不存在，请先运行提取")
            return

    if args.extract_only:
        return

    # 评估聚类
    results = evaluate_clustering(features)

    # 最优方案
    if results:
        best = results[0]
        log.info("\n" + "=" * 70)
        log.info("最优方案: %s %s %s", best[0], best[1], best[2])
        log.info("  簇数: %d, 噪音: %d", best[3], best[4])
        log.info("  轮廓系数: %.4f", best[5])
        log.info("  Davies-Bouldin: %.4f", best[6])
        log.info("  簇内相似度: %.4f", best[7])
        log.info("=" * 70)

        if args.recluster:
            log.info("按最优方案重新分配学生...")
            # 获取所有 face_record ids
            fr_ids = list(features.keys())
            X = np.array([features[fid] for fid in fr_ids], dtype=np.float32)
            norms = np.linalg.norm(X, axis=1, keepdims=True)
            norms[norms < 1e-10] = 1
            X_norm = X / norms

            # 运行最优方案
            algo = best[0]
            if algo == 'DBSCAN':
                model = DBSCAN(eps=best[1], min_samples=int(best[2]), metric='cosine', n_jobs=-1)
                labels = model.fit_predict(X_norm)
            elif algo == 'HDBSCAN':
                import hdbscan
                model = hdbscan.HDBSCAN(min_cluster_size=int(best[1]), min_samples=int(best[2]),
                                         metric='euclidean')
                labels = model.fit_predict(X_norm)
            elif algo == 'Agglomerative':
                model = AgglomerativeClustering(n_clusters=int(best[1]), linkage='complete', metric='cosine')
                labels = model.fit_predict(X_norm)
            else:  # KMeans
                model = KMeans(n_clusters=int(best[1]), random_state=42, n_init=5)
                labels = model.fit_predict(X_norm)

            # 应用聚类结果到数据库
            conn = db_connect()
            cur = conn.cursor()
            
            # 清除旧的学生关联
            cur.execute("UPDATE face_record SET student_id = NULL")
            cur.execute("DELETE FROM student WHERE id NOT IN (1)")
            cur.execute("SELECT setval('student_id_seq', 2)")
            conn.commit()

            # 为每个簇创建学生
            from collections import Counter
            cluster_groups = defaultdict(list)
            for i, label in enumerate(labels):
                if label >= 0:
                    cluster_groups[int(label)].append(fr_ids[i])

            next_id = 2
            for label, members in cluster_groups.items():
                if len(members) < 3:
                    continue
                name = f'学生{next_id:03d}'
                sno = f'stu{next_id:04d}'
                cur.execute("INSERT INTO student (id, name, student_no, status, class_id) VALUES (%s, %s, %s, 'active', 1)",
                           (next_id, name, sno))
                for fid in members:
                    cur.execute("UPDATE face_record SET student_id = %s WHERE id = %s", (next_id, fid))
                next_id += 1

            conn.commit()
            cur.execute("SELECT setval('student_id_seq', %s)", (next_id,))
            cur.execute("SELECT COUNT(*) FROM face_record WHERE student_id IS NOT NULL")
            log.info("已完成: %d 学生, %d 条 face_record 关联", next_id - 2, cur.fetchone()[0])
            cur.close()
            conn.close()


if __name__ == '__main__':
    main()
