"""Feature clustering: Qdrant scroll → numpy cosine graph → core-expansion DBSCAN.

Ported from FaceClusteringServiceV2.java with numpy acceleration.
"""

import json
import logging
import re
import time
from collections import defaultdict
from datetime import datetime

import numpy as np
import psycopg2
import requests

from scripts.pipeline.config import (
    DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS,
    QDRANT_URL, QDRANT_COLLECTION,
    CLUSTER_SIMILARITY_THRESHOLD, CLUSTER_MIN_CORE,
    CLUSTER_CENTROID_MERGE, CLUSTER_MIN_SIZE,
    CLUSTER_MIN_CONFIDENCE, SPATIAL_SEAT_DIST,
    MIN_FACE_WIDTH,
)

log = logging.getLogger(__name__)


def db_connect():
    return psycopg2.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER,
        password=DB_PASS, database=DB_NAME,
    )


# ── Qdrant scroll ──

def scroll_all_points():
    """Fetch all points with vectors from Qdrant collection."""
    result = []
    offset = None
    while True:
        body = {"limit": 1000, "with_vector": True, "with_payload": True}
        if offset is not None:
            body["offset"] = offset
        resp = requests.post(
            f"{QDRANT_URL}/collections/{QDRANT_COLLECTION}/points/scroll",
            json=body, timeout=60,
        )
        data = resp.json()
        points = data.get("result", {}).get("points", [])
        if not points:
            break
        result.extend(points)
        if len(points) < 1000:
            break
        offset = points[-1]["id"]
    return result


# ── Face metadata ──

def parse_bbox_width(bbox_json):
    if not bbox_json:
        return 0
    try:
        bbox = json.loads(bbox_json) if isinstance(bbox_json, str) else bbox_json
        return int(bbox.get("width", 0))
    except Exception:
        return 0


def parse_bbox_center(bbox_json):
    if not bbox_json:
        return 0.0, 0.0
    try:
        bbox = json.loads(bbox_json) if isinstance(bbox_json, str) else bbox_json
        cx = float(bbox["x"]) + float(bbox["width"]) / 2.0
        cy = float(bbox["y"]) + float(bbox["height"]) / 2.0
        return cx, cy
    except Exception:
        return 0.0, 0.0


def load_face_meta(face_record_ids):
    """Batch-load face_record metadata from PG."""
    conn = db_connect()
    cur = conn.cursor()
    meta = {}
    ids = list(set(fid for fid in face_record_ids if fid > 0))
    batch_size = 500
    for i in range(0, len(ids), batch_size):
        batch = ids[i:i + batch_size]
        cur.execute(
            """SELECT fr.id, fr.confidence, fr.bbox, ci.class_id
               FROM face_record fr
               JOIN class_image ci ON ci.id = fr.class_image_id
               WHERE fr.id = ANY(%s)""",
            (batch,),
        )
        for row in cur.fetchall():
            fr_id, conf, bbox, cid = row
            cx, cy = parse_bbox_center(bbox)
            meta[fr_id] = {
                "confidence": conf,
                "face_width": parse_bbox_width(bbox),
                "center_x": cx,
                "center_y": cy,
                "class_id": cid,
            }
    cur.close()
    conn.close()
    return meta


# ── Spatial constraint ──

def same_seat(meta_i, meta_j):
    """Check if two faces are within spatial seat distance."""
    if meta_i is None or meta_j is None:
        return True
    dx = meta_i["center_x"] - meta_j["center_x"]
    dy = meta_i["center_y"] - meta_j["center_y"]
    return (dx * dx + dy * dy) ** 0.5 <= SPATIAL_SEAT_DIST


# ── Cosine similarity (vectorized) ──

def cosine_similarity_matrix(vectors):
    """Compute n×n cosine similarity matrix (numpy vectorized)."""
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    norms[norms < 1e-10] = 1.0
    normalized = vectors / norms
    return normalized @ normalized.T


# ── BFS connected components ──

def bfs_clusters(adj_list, min_size):
    """Find connected components in adjacency list."""
    n = len(adj_list)
    visited = [False] * n
    clusters = []
    for i in range(n):
        if not visited[i]:
            comp = []
            queue = [i]
            visited[i] = True
            while queue:
                node = queue.pop(0)
                comp.append(node)
                for nb in adj_list[node]:
                    if not visited[nb]:
                        visited[nb] = True
                        queue.append(nb)
            if len(comp) >= min_size:
                clusters.append(comp)
    return clusters


# ── Core-expansion DBSCAN ──

def core_expansion_clusters(adj_list, vectors, min_core, min_cluster_size):
    """Core-expansion DBSCAN: core points seed clusters, expand to neighbors."""
    n = len(adj_list)
    is_core = [len(adj_list[i]) >= min_core for i in range(n)]
    core_count = sum(is_core)
    log.info("  Core points: %d/%d (min_core=%d)", core_count, n, min_core)

    if core_count == 0:
        log.warning("  No core points, falling back to BFS")
        return bfs_clusters(adj_list, min_cluster_size)

    # Build core-only graph
    core_map = {}
    core_indices = []
    for i in range(n):
        if is_core[i]:
            core_map[i] = len(core_indices)
            core_indices.append(i)

    core_adj = [[] for _ in range(len(core_indices))]
    for ci in core_indices:
        cidx = core_map[ci]
        for nb in adj_list[ci]:
            if is_core[nb] and nb in core_map:
                core_adj[cidx].append(core_map[nb])

    core_clusters = bfs_clusters(core_adj, 1)
    log.info("  Core clusters before expansion: %d", len(core_clusters))

    # Expand: assign non-core neighbors
    assigned = set()
    result = []
    for cc in core_clusters:
        cluster_core_idx = [core_indices[idx] for idx in cc]
        cluster = list(cluster_core_idx)
        assigned.update(cluster_core_idx)

        # Centroid
        vecs_in_cluster = [
            vectors[i] for i in cluster_core_idx if vectors[i] is not None
        ]
        centroid = np.mean(vecs_in_cluster, axis=0) if vecs_in_cluster else None

        # Collect candidate neighbors
        candidates = set()
        for ci in cluster_core_idx:
            for nb in adj_list[ci]:
                if nb not in assigned:
                    candidates.add(nb)

        for cand in candidates:
            if is_core[cand] and cand not in assigned:
                continue
            if centroid is not None and vectors[cand] is not None:
                sim = float(
                    np.dot(centroid, vectors[cand])
                    / (np.linalg.norm(centroid) * np.linalg.norm(vectors[cand]) + 1e-10)
                )
                if sim >= 0.7:
                    cluster.append(cand)
                    assigned.add(cand)

        result.append(cluster)

    result = [c for c in result if len(c) >= min_cluster_size]
    return result


# ── Centroid merge ──

def centroid_merge(clusters, vectors, merge_threshold):
    """Merge clusters with highly similar centroids."""
    if len(clusters) <= 1:
        return clusters

    centroids = []
    for c in clusters:
        vecs = [vectors[i] for i in c if vectors[i] is not None]
        centroids.append(np.mean(vecs, axis=0) if vecs else None)

    # Merge graph
    n = len(clusters)
    adj = [[] for _ in range(n)]
    merge_count = 0
    for i in range(n):
        for j in range(i + 1, n):
            if centroids[i] is not None and centroids[j] is not None:
                sim = float(
                    np.dot(centroids[i], centroids[j])
                    / (np.linalg.norm(centroids[i])
                       * np.linalg.norm(centroids[j]) + 1e-10)
                )
                if sim >= merge_threshold:
                    adj[i].append(j)
                    adj[j].append(i)
                    merge_count += 1

    if merge_count == 0:
        log.info("  No centroid merges needed")
        return clusters

    # BFS merge
    visited = [False] * n
    merged = []
    for i in range(n):
        if not visited[i]:
            comp = []
            queue = [i]
            visited[i] = True
            while queue:
                node = queue.pop(0)
                comp.append(node)
                for nb in adj[node]:
                    if not visited[nb]:
                        visited[nb] = True
                        queue.append(nb)
            if len(comp) > 1:
                mc = []
                for ci in comp:
                    mc.extend(clusters[ci])
                merged.append(mc)
            else:
                merged.append(clusters[comp[0]])

    log.info("  Centroid merge: %d merges, %d → %d clusters",
             merge_count, len(clusters), len(merged))
    return merged


# ── Derive class ID ──

def derive_class_id(cluster_indices, class_ids):
    """Majority vote for class_id within cluster."""
    votes = defaultdict(int)
    for idx in cluster_indices:
        cid = class_ids[idx]
        if cid and cid > 0:
            votes[cid] += 1
    if not votes:
        return None
    return max(votes, key=votes.get)


# ── Main clustering entry ──

def run_clustering():
    """Full clustering pipeline: Qdrant → filter → graph → cluster → DB."""
    start = datetime.now()
    log.info("=== Clustering Pipeline ===")

    # 1. Scroll all Qdrant points
    raw_points = scroll_all_points()
    if not raw_points:
        log.warning("No points in Qdrant, aborting clustering")
        return {"clusters": 0, "total_faces": 0, "outliers": 0}
    log.info("Loaded %d points from Qdrant", len(raw_points))

    # 2. Extract vectors and face_record_ids
    fr_ids = []
    vectors_list = []
    for pt in raw_points:
        vid = pt.get("id")
        vec = pt.get("vector")
        if vid is None or vec is None:
            continue
        try:
            fr_id = int(vid)
        except (ValueError, TypeError):
            continue
        fr_ids.append(fr_id)
        vectors_list.append(vec)

    if not fr_ids:
        log.warning("No valid points with vectors")
        return {"clusters": 0, "total_faces": 0, "outliers": 0}

    # 3. Load face metadata
    face_meta = load_face_meta(fr_ids)
    log.info("Face metadata loaded for %d records", len(face_meta))

    # 4. Filter by quality
    filtered_indices = []
    class_ids = []
    filter_stats = {"conf": 0, "size": 0, "no_meta": 0}
    for i, fr_id in enumerate(fr_ids):
        meta = face_meta.get(fr_id)
        if meta is None:
            filter_stats["no_meta"] += 1
            continue
        if (meta["confidence"] is not None
                and meta["confidence"] < CLUSTER_MIN_CONFIDENCE):
            filter_stats["conf"] += 1
            continue
        if meta["face_width"] < MIN_FACE_WIDTH:
            filter_stats["size"] += 1
            continue
        filtered_indices.append(i)
        class_ids.append(meta["class_id"])

    n = len(filtered_indices)
    log.info("After filtering: %d remained "
             "(conf<%.1f: %d, w<%d: %d, noMeta: %d)",
             n, CLUSTER_MIN_CONFIDENCE, filter_stats["conf"],
             MIN_FACE_WIDTH, filter_stats["size"], filter_stats["no_meta"])

    if n < 2:
        log.warning("Insufficient points after filtering (n=%d)", n)
        return {
            "clusters": 0,
            "total_faces": len(raw_points),
            "outliers": len(raw_points),
        }

    # 5. Build similarity graph (vectorized)
    sub_vectors = np.array(
        [vectors_list[i] for i in filtered_indices], dtype=np.float32
    )
    cos_mat = cosine_similarity_matrix(sub_vectors)
    threshold_mask = cos_mat >= CLUSTER_SIMILARITY_THRESHOLD

    adj_list = [[] for _ in range(n)]
    edge_count = 0
    for i in range(n):
        for j in range(i + 1, n):
            if threshold_mask[i, j]:
                fi = filtered_indices[i]
                fj = filtered_indices[j]
                mi = face_meta.get(fr_ids[fi])
                mj = face_meta.get(fr_ids[fj])
                if same_seat(mi, mj):
                    adj_list[i].append(j)
                    adj_list[j].append(i)
                    edge_count += 1

    log.info("Similarity graph: %d edges among %d nodes", edge_count, n)

    # 6. Core-expansion clustering
    clusters = core_expansion_clusters(
        adj_list, sub_vectors, CLUSTER_MIN_CORE, CLUSTER_MIN_SIZE
    )

    # 7. Centroid merge
    clusters = centroid_merge(clusters, sub_vectors, CLUSTER_CENTROID_MERGE)

    outliers = n - sum(len(c) for c in clusters)
    log.info("Final: %d clusters, %d outliers", len(clusters), outliers)

    # 8. Save clusters to PG
    conn = db_connect()
    cur = conn.cursor()
    saved = 0
    for cluster in clusters:
        cluster_key = f"qc_{int(time.time())}_{saved}"
        face_ids = [fr_ids[filtered_indices[idx]] for idx in cluster]
        f_indices = [filtered_indices[idx] for idx in cluster]
        cid = derive_class_id(f_indices, class_ids)

        cur.execute(
            """INSERT INTO face_cluster
               (cluster_key, class_id, face_tokens, sample_count,
                first_seen_at, last_seen_at, status)
               VALUES (%s, %s, %s, %s, now(), now(), 'pending')""",
            (cluster_key, cid, json.dumps([str(fid) for fid in face_ids]),
             len(face_ids)),
        )
        saved += 1

    conn.commit()

    elapsed = (datetime.now() - start).total_seconds()
    log.info("Clustering done: %d clusters saved in %.1fs", saved, elapsed)

    # 9. Auto-annotate
    auto_annotate(cur, conn)

    cur.close()
    conn.close()

    return {
        "clusters": saved,
        "total_faces": len(raw_points),
        "outliers": outliers,
        "elapsed_seconds": elapsed,
    }


def auto_annotate(cur, conn):
    """Create student records for pending clusters,
    backfill face_record.student_id."""
    cur.execute(
        """SELECT id, class_id, face_tokens, student_id
           FROM face_cluster WHERE status = 'pending'"""
    )
    clusters = cur.fetchall()
    if not clusters:
        log.info("No pending clusters to auto-annotate")
        return

    log.info("Auto-annotating %d clusters", len(clusters))
    for cid, class_id, face_tokens, existing_student_id in clusters:
        if existing_student_id:
            continue
        if not class_id or class_id == 0:
            continue

        cur.execute(
            "SELECT COUNT(*) FROM student WHERE student_no LIKE %s",
            (f"auto_{class_id}_%",),
        )
        count = cur.fetchone()[0]
        seq = count + 1
        student_no = f"auto_{class_id}_{cid}"
        student_name = f"student{seq:03d}"

        cur.execute(
            """INSERT INTO student (name, student_no, status, class_id)
               VALUES (%s, %s, 'active', %s) RETURNING id""",
            (student_name, student_no, class_id),
        )
        student_id = cur.fetchone()[0]

        # Backfill face_record.student_id
        try:
            ids = json.loads(face_tokens)
            for id_str in ids:
                try:
                    fr_id = int(id_str)
                    cur.execute(
                        """UPDATE face_record SET student_id = %s
                           WHERE id = %s AND student_id IS NULL""",
                        (student_id, fr_id),
                    )
                except (ValueError, TypeError):
                    pass
        except json.JSONDecodeError:
            # Regex fallback
            for m in re.finditer(r'"?(\\d+)"?', str(face_tokens)):
                try:
                    fr_id = int(m.group(1))
                    cur.execute(
                        """UPDATE face_record SET student_id = %s
                           WHERE id = %s AND student_id IS NULL""",
                        (student_id, fr_id),
                    )
                except (ValueError, TypeError):
                    pass

        cur.execute(
            """UPDATE face_cluster SET student_id = %s, status = 'auto_annotated'
               WHERE id = %s""",
            (student_id, cid),
        )

    conn.commit()
    log.info("Auto-annotation complete")


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
    )
    result = run_clustering()
    print(json.dumps(result, indent=2, default=str))
