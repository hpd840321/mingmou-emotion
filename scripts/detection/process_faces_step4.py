#!/usr/bin/env python3
"""
Step 4 v2: Face Clustering via Core-Expansion + Centroid Merge
对 Qdrant 中的 512 维向量做 ANN 搜索 → 核心点扩展聚类 → 质心合并

改进:
  1. 核心点扩展 (DBSCAN-like): 只允许"核心点"发起聚类，切断传递闭包链
  2. 质心合并: 聚类后合并质心高度相似的簇（同一人不同角度产生分裂时合并）
  3. 质量过滤: conf ≥ 0.5, face_width ≥ 50

Usage:
  python3 process_faces_step4.py --threshold 0.85 --min-core 8 --merge 0.92
  python3 process_faces_step4.py --dry-run
"""
import os, sys, json, time, re, math
from datetime import datetime, date
import argparse, logging
from collections import defaultdict
import requests, pymysql

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

QDRANT_URL = 'http://localhost:6333'
COLLECTION = 'face_features_512'
DB_HOST = 'nexus.craftsupport.cn'
DB_PORT = 3307
DB_USER = 'root'
DB_PASS = '123456'
DB_NAME = 'emotion_platform'
CHECKPOINT_FILE = '/tmp/face_clustering_v2_checkpoint.json'
ANN_SEARCH_LIMIT = 50


def db_connect():
    return pymysql.connect(host=DB_HOST, port=DB_PORT, user=DB_USER,
                           password=DB_PASS, database=DB_NAME,
                           charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor)

def save_checkpoint(data):
    tmp = CHECKPOINT_FILE + '.tmp'
    with open(tmp, 'w') as f: json.dump(data, f)
    os.replace(tmp, CHECKPOINT_FILE)

def load_checkpoint():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f: return json.load(f)
    return {}

def get_all_point_ids():
    ids = []
    offset = None
    while True:
        body = {'limit': 50000, 'with_vector': False, 'with_payload': False}
        if offset: body['offset'] = offset
        r = requests.post(f'{QDRANT_URL}/collections/{COLLECTION}/points/scroll', json=body, timeout=30)
        pts = r.json().get('result', {}).get('points', [])
        if not pts: break
        ids.extend(p['id'] for p in pts)
        if len(pts) < 50000: break
        offset = pts[-1]['id']
    return ids

def get_vector(pid):
    r = requests.post(f'{QDRANT_URL}/collections/{COLLECTION}/points/scroll',
                      json={'limit': 1, 'with_vector': True, 'filter': {'must': [{'has_id': [pid]}]}}, timeout=10)
    pts = r.json().get('result', {}).get('points', [])
    return pts[0].get('vector') if pts else None

def ann_search(vec, threshold, limit=ANN_SEARCH_LIMIT):
    r = requests.post(f'{QDRANT_URL}/collections/{COLLECTION}/points/search',
                      json={'vector': vec, 'limit': limit, 'score_threshold': threshold, 'with_payload': False}, timeout=30)
    return r.json().get('result', [])

def cosine_sim(a, b):
    dot = sum(x*y for x,y in zip(a,b))
    na = math.sqrt(sum(x*x for x in a))
    nb = math.sqrt(sum(y*y for y in b))
    return dot / (na * nb) if na * nb > 1e-10 else 0

def compute_centroid(vectors):
    if not vectors: return None
    dim = len(vectors[0])
    c = [0.0] * dim
    for v in vectors: c = [c[i]+v[i] for i in range(dim)]
    n = len(vectors)
    return [c[i]/n for i in range(dim)]


# ================================================================
#  Core-Expansion Clustering (DBSCAN-like)
# ================================================================

def core_expansion_clusters(graph, vectors, all_ids, min_core, min_cluster):
    """
    Core-expansion clustering:
      1. Identify core points: neighbors ≥ min_core
      2. Build core-graph (edges only between cores)
      3. BFS core-graph → core clusters
      4. Assign non-core points to nearest cluster centroid
      5. Filter clusters ≥ min_cluster
    Returns: list of clusters (each cluster = list of point IDs)
    """
    n = len(all_ids)
    id_to_idx = {pid: i for i, pid in enumerate(all_ids)}

    # Step 1: Identify cores
    is_core = [len(graph.get(i, set())) >= min_core for i in range(n)]
    core_count = sum(is_core)
    log.info(f'  Core points: {core_count}/{n} (min_neighbors={min_core})')

    if core_count == 0:
        log.warning('  No core points found — falling back to BFS')
        return bfs_fallback(graph, all_ids, min_cluster)

    # Step 2: Core-graph BFS
    core_indices = [i for i in range(n) if is_core[i]]
    core_to_new = {ci: ni for ni, ci in enumerate(core_indices)}
    core_graph = defaultdict(set)
    for ci in core_indices:
        for nb in graph.get(ci, set()):
            if is_core[nb]:
                core_graph[core_to_new[ci]].add(core_to_new[nb])

    visited = [False] * len(core_indices)
    core_clusters = []
    for i in range(len(core_indices)):
        if not visited[i]:
            comp = []
            stack = [i]; visited[i] = True
            while stack:
                node = stack.pop(); comp.append(node)
                for nb in core_graph.get(node, set()):
                    if not visited[nb]: visited[nb] = True; stack.append(nb)
            core_clusters.append([core_indices[ci] for ci in comp])

    log.info(f'  Core clusters before expansion: {len(core_clusters)}')

    # Step 3: Assign non-core points to nearest cluster centroid
    clusters = []
    all_assigned = set()
    for cc in core_clusters:
        cluster = list(cc)
        all_assigned.update(cc)
        # Compute centroid from core points
        centroid = compute_centroid([vectors[i] for i in cc])
        # Assign non-core neighbors
        candidates = set()
        for ci in cc:
            candidates.update(graph.get(ci, set()))
        candidates -= all_assigned
        for cand in candidates:
            if is_core[cand] and cand not in all_assigned:
                continue  # unvisited core → belongs to another cluster
            if centroid and vectors[cand]:
                sim = cosine_sim(centroid, vectors[cand])
                if sim >= 0.7:  # softer threshold for non-core assignment
                    cluster.append(cand)
                    all_assigned.add(cand)
        clusters.append(cluster)

    # Step 4: Filter by min_cluster
    clusters = [c for c in clusters if len(c) >= min_cluster]
    outliers = n - sum(len(c) for c in clusters)
    log.info(f'  After expansion: {len(clusters)} clusters, {outliers} outliers')
    return [[all_ids[idx] for idx in c] for c in clusters]


def bfs_fallback(graph, all_ids, min_cluster):
    """Fallback BFS when no core points found."""
    n = len(all_ids)
    visited = [False] * n
    clusters = []
    for i in range(n):
        if not visited[i]:
            comp = []; stack = [i]; visited[i] = True
            while stack:
                node = stack.pop(); comp.append(node)
                for nb in graph.get(node, set()):
                    if not visited[nb]: visited[nb] = True; stack.append(nb)
            if len(comp) >= min_cluster:
                clusters.append([all_ids[idx] for idx in comp])
    return clusters


# ================================================================
#  Centroid Merge
# ================================================================

def centroid_merge(clusters, vectors, all_ids, merge_threshold):
    """
    Merge clusters whose centroids have cosine similarity ≥ merge_threshold.
    Runs iteratively until no more merges.
    """
    if len(clusters) <= 1: return clusters

    id_to_idx = {pid: i for i, pid in enumerate(all_ids)}

    # Compute centroids
    centroids = []
    for c in clusters:
        vecs = [vectors[id_to_idx[pid]] for pid in c if pid in id_to_idx and vectors[id_to_idx[pid]]]
        centroids.append(compute_centroid(vecs))

    # Build merge graph
    merge_graph = defaultdict(set)
    for i in range(len(clusters)):
        for j in range(i+1, len(clusters)):
            if centroids[i] and centroids[j]:
                sim = cosine_sim(centroids[i], centroids[j])
                if sim >= merge_threshold:
                    merge_graph[i].add(j)
                    merge_graph[j].add(i)

    if not merge_graph:
        log.info('  No centroid merges needed')
        return clusters

    # BFS merge
    visited = [False] * len(clusters)
    merged = []
    merges_done = 0
    for i in range(len(clusters)):
        if not visited[i]:
            comp = []; stack = [i]; visited[i] = True
            while stack:
                node = stack.pop(); comp.append(node)
                for nb in merge_graph.get(node, set()):
                    if not visited[nb]: visited[nb] = True; stack.append(nb)
            if len(comp) > 1:
                merges_done += 1
                merged.append([pid for ci in comp for pid in clusters[ci]])
            else:
                merged.append(clusters[comp[0]])

    log.info(f'  Centroid merge: {merges_done} merges, {len(clusters)} → {len(merged)} clusters')
    return merged


# ================================================================
#  Class ID derivation
# ================================================================

def derive_class_id(cluster_ids, face_meta):
    votes = {}
    for fid in cluster_ids:
        meta = face_meta.get(int(fid))
        if meta and meta.get('class_id'):
            cid = meta['class_id']
            votes[cid] = votes.get(cid, 0) + 1
    return max(votes, key=votes.get) if votes else None


# ================================================================
#  Auto-Annotation
# ================================================================

def auto_annotate_clusters(cursor, db, dry_run=False):
    cursor.execute("SELECT * FROM face_cluster WHERE status='pending' ORDER BY id")
    clusters = cursor.fetchall()
    log.info('Auto-annotating %d pending clusters...', len(clusters))
    created = 0
    for fc in clusters:
        cluster_id = fc['id']; class_id = fc.get('class_id') or 1
        if dry_run:
            log.info('  [DRY] Cluster #%d: would create student, %d faces', cluster_id, fc['sample_count'])
            continue
        cursor.execute("SELECT COUNT(*) cnt FROM student WHERE student_no LIKE %s", (f'auto_{class_id}_%',))
        seq = cursor.fetchone()['cnt'] + 1
        student_no = f'auto_{class_id}_{cluster_id}'; student_name = f'student{seq:03d}'
        cursor.execute("INSERT INTO student (student_no, name, class_id, status) VALUES (%s,%s,%s,'active')",
                       (student_no, student_name, class_id))
        student_id = cursor.lastrowid
        face_tokens = fc.get('face_tokens', '[]')
        try: ids = json.loads(face_tokens) if isinstance(face_tokens, str) else face_tokens
        except: ids = []
        backfilled = 0
        for lid in ids:
            cursor.execute("UPDATE face_record SET student_id=%s WHERE id=%s AND student_id IS NULL", (student_id, lid))
            backfilled += cursor.rowcount
        cursor.execute("UPDATE face_cluster SET student_id=%s, status='auto_annotated' WHERE id=%s", (student_id, cluster_id))
        db.commit()
        log.info('  Cluster #%d → student #%d %s (%d faces)', cluster_id, student_id, student_name, backfilled)
        created += 1
    log.info('Auto-annotation complete: %d clusters', created)
    return created


# ================================================================
#  Main
# ================================================================

def main():
    parser = argparse.ArgumentParser(description='Step 4 v2: Core-Expansion + Centroid Merge Clustering')
    parser.add_argument('--threshold', type=float, default=0.85, help='ANN similarity threshold')
    parser.add_argument('--min-core', type=int, default=8, help='Min neighbors for core point')
    parser.add_argument('--min-cluster', type=int, default=8, help='Min faces per cluster')
    parser.add_argument('--merge', type=float, default=0.92, help='Centroid merge threshold')
    parser.add_argument('--min-confidence', type=float, default=0.5)
    parser.add_argument('--min-face-width', type=int, default=50)
    parser.add_argument('--max-seat-dist', type=int, default=200, help='Max bbox center distance for same person (px)')
    parser.add_argument('--parallel', type=int, default=4, help='ANN search workers')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--annotate-only', action='store_true')
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()

    log.info('=== Step 4 v2: Core-Expansion + Centroid Merge + Spatial ===')
    log.info('threshold=%.2f min_core=%d min_cluster=%d merge=%.2f seat_dist=%d parallel=%d',
             args.threshold, args.min_core, args.min_cluster, args.merge, args.max_seat_dist, args.parallel)

    db = db_connect(); cursor = db.cursor()

    # Quality filter
    cursor.execute("""SELECT fr.id, fr.confidence, fr.bbox, ci.class_id FROM face_record fr
        JOIN class_image ci ON ci.id=fr.class_image_id
        WHERE fr.face_encoding IS NOT NULL AND fr.face_encoding!='' AND fr.confidence>=%s""", (args.min_confidence,))
    face_meta = {}; bbox_f = 0; bbox_centers = {}
    for r in cursor.fetchall():
        try:
            b = json.loads(r['bbox'])
            w = int(b.get('width',0))
            cx = float(b['x']) + w/2
            cy = float(b['y']) + float(b['height'])/2
        except: w = 0; cx = 0; cy = 0
        if w < args.min_face_width: bbox_f += 1; continue
        face_meta[r['id']] = {'confidence': r['confidence'], 'class_id': r['class_id']}
        bbox_centers[r['id']] = (cx, cy)
    log.info(f'Qualified: {len(face_meta)} faces (bbox filtered: {bbox_f})')

    if args.annotate_only:
        n = auto_annotate_clusters(cursor, db, args.dry_run)
        log.info(f'Annotated: {n}'); return

    cp = load_checkpoint() if args.resume else {}
    processed_set = set(cp.get('processed_ids', []))

    raw_ids = get_all_point_ids()
    all_ids = [pid for pid in raw_ids if int(pid) in face_meta]
    log.info(f'Points: {len(raw_ids)} raw → {len(all_ids)} qualified')
    if not all_ids: return

    ids_to_process = [pid for pid in all_ids if pid not in processed_set] if args.resume else list(all_ids)
    log.info(f'To process: {len(ids_to_process)}')

    # Build graph + collect vectors
    id_to_idx = {pid: i for i, pid in enumerate(all_ids)}
    n = len(all_ids)
    graph = defaultdict(set)
    vectors = [None] * n
    t0 = time.time(); done = 0; batch_n = 0
    from concurrent.futures import ThreadPoolExecutor, as_completed

    def search_point(pid):
        vec = get_vector(pid)
        if not vec: return pid, [], None
        results = ann_search(vec, args.threshold)
        # Spatial filter: only keep neighbors within max_seat_dist
        pc = bbox_centers.get(int(pid))
        neighbors = []
        for r in results:
            nid = r['id']
            if nid == pid: continue
            nc = bbox_centers.get(int(nid))
            if pc and nc:
                dist = ((pc[0]-nc[0])**2 + (pc[1]-nc[1])**2)**0.5
                if dist > args.max_seat_dist: continue  # different seat
            neighbors.append(nid)
        return pid, neighbors, vec

    for bs in range(0, len(ids_to_process), args.parallel*2):
        batch = ids_to_process[bs:bs + args.parallel*2]
        with ThreadPoolExecutor(max_workers=args.parallel) as ex:
            futs = {ex.submit(search_point, p): p for p in batch}
            for fut in as_completed(futs):
                pid, neighbors, vec = fut.result()
                idx = id_to_idx.get(pid)
                if idx is not None:
                    if vec: vectors[idx] = vec
                    if neighbors:
                        graph[idx].update(id_to_idx[nb] for nb in neighbors if nb in id_to_idx)
                        for nb in neighbors:
                            if nb in id_to_idx: graph[id_to_idx[nb]].add(idx)
                done += 1; batch_n += 1

        if batch_n >= 500:
            elapsed = time.time()-t0; rate = done/elapsed if elapsed>0 else 0
            log.info(f'  ANN: {done}/{len(ids_to_process)} ({rate:.1f}/s, ETA {(len(ids_to_process)-done)/rate/60:.0f}min)')
            cp['processed_ids'] = sorted(set(cp.get('processed_ids',[])) | {str(p) for p in ids_to_process[:bs+len(batch)]})
            save_checkpoint(cp); batch_n = 0

    elapsed = time.time()-t0
    log.info(f'Graph: {n} nodes, {sum(len(v) for v in graph.values())//2} edges ({elapsed/60:.1f}min)')

    # Core-Expansion
    clusters = core_expansion_clusters(graph, vectors, all_ids, args.min_core, args.min_cluster)
    log.info(f'Core-expansion: {len(clusters)} clusters')

    # Centroid Merge
    clusters = centroid_merge(clusters, vectors, all_ids, args.merge)
    log.info(f'Final: {len(clusters)} clusters')

    # Size distribution
    size_dist = defaultdict(int)
    for c in clusters: size_dist[len(c)] += 1
    for sz in sorted(size_dist)[:15]:
        log.info(f'  size={sz}: {size_dist[sz]} clusters')

    # Save
    if args.dry_run:
        log.info(f'[DRY] Would save {len(clusters)} clusters')
    else:
        saved = 0
        for ci, cids in enumerate(clusters):
            ts = datetime.now().strftime('%Y%m%d%H%M%S')
            ck = f'qcv2_{ts}_{ci}'; fid_json = json.dumps(cids)
            class_id = derive_class_id(cids, face_meta)
            cursor.execute("INSERT INTO face_cluster (cluster_key,class_id,face_tokens,sample_count,status,first_seen_at,last_seen_at) VALUES (%s,%s,%s,%s,'pending',NOW(),NOW())",
                           (ck, class_id, fid_json, len(cids)))
            saved += 1
            if saved % 200 == 0: db.commit()
        db.commit()
        log.info(f'Saved {saved} clusters')
        auto_annotate_clusters(cursor, db)

    cp.update({'clusters': len(clusters), 'completed': datetime.now().isoformat()})
    save_checkpoint(cp)
    log.info(f'\n=== Done: {len(clusters)} clusters ===')
    cursor.close(); db.close()

if __name__ == '__main__':
    main()
