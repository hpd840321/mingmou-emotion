# Pipeline Redesign: End-to-End Python Face Processing

**Date:** 2026-06-15
**Status:** approved
**Scope:** Complete reimplementation of face detection → feature extraction → Qdrant storage → clustering as a single Python pipeline

## 1. Motivation

The current architecture splits face processing across three disconnected layers:

1. `init_data_pipeline.py` — gRPC detection + 1:N matching (single GPU, serial)
2. `extract_features_512_pg.py` — CPU ONNX feature extraction (dead code path)
3. `FaceClusteringServiceV2.java` — Java-side Qdrant clustering

Problems:
- Single gRPC connection cannot use both GPUs
- Serial image processing (4560 images → 3-5 hours)
- Features stored as base64 in PG, not pushed to Qdrant
- Online 1:N matching instead of offline batch clustering
- No integration between Python extraction and Java clustering
- Hardcoded class_id
- Emotion loss (labels only, no probability vector)

## 2. New Architecture

### 2.1 Overview

A single Python pipeline (`scripts/pipeline/`) that handles the entire flow:

```
data/*.jpg → gRPC (dual GPU) → filter → crop → PG + Qdrant → clustering → student
```

### 2.2 File Structure

```
scripts/pipeline/
  config.py         — All config constants (thresholds, endpoints, paths)
  grpc_client.py    — Dual-endpoint gRPC stub pool (GPU 0:50053, GPU 1:50054)
  processor.py      — Single-image processing: detect/filter/crop/PG/Qdrant
  worker.py         — Thread worker with PG connection pool + Qdrant batching
  clustering.py     — Feature clustering + auto-annotate (ported from Java)
  main.py           — Entry: cleanup → Qdrant → parallel workers → cluster
  cleanup.py        — PG + Qdrant data cleanup
```

### 2.3 Data Flow Per Image

```
1. Read image bytes from disk
2. gRPC AnalyzeRequest(image, enabled_features=0xB3)
   → returns per face: bbox, confidence, 512-dim feature, 7-dim emotion, gender, quality
3. Filter: confidence ≥ 0.3, bbox width ≥ 50px
4. For each valid face:
   a. Crop (Pillow, bbox + 30% margin) → save JPEG to images/cropped/
   b. INSERT face_record (bbox, confidence, quality, face_encoding, gender)
   c. INSERT emotion_record (dominant_emotion, all 7 probs, dominant_state)
   d. Queue Qdrant point: {id: face_record_id, vector: float32[512], payload: {face_record_id, class_image_id}}
5. UPDATE class_image (face_detected_count, emotion_recognized_count)
6. Batch flush Qdrant (every 200 points)
```

### 2.4 Dual GPU Parallelization

```
main.py
  ├── ThreadPoolExecutor(max_workers=2)
  │   ├── Worker-0 → gRPC localhost:50053 (GPU 0 TensorRT)
  │   └── Worker-1 → gRPC localhost:50054 (GPU 1 TensorRT)
  ├── Shared task queue: List[ImageTask] from data/ scan
  ├── Each worker has its own PG connection and Qdrant batch buffer
  └── PG writes protected by per-worker connection (no cross-thread sharing)
```

### 2.5 Clustering Algorithm (Python + numpy)

Ported from `FaceClusteringServiceV2.java` with numpy acceleration:

1. `qdrant_scroll_all()` — fetch all points with vectors from `face_features_512`
2. `load_face_meta()` — batch-load face_record confidence + bbox + class_id from PG
3. Filter: confidence ≥ 0.5, face_width ≥ 50px
4. `normalized @ normalized.T` → n×n cosine similarity matrix (vectorized, replaces O(n²) double loop)
5. Threshold graph: `adj = (cos_mat ≥ 0.85) AND sameSeat(i, j)` (spatial constraint)
6. Core-expansion DBSCAN (min_core=8): only high-degree points seed clusters
7. Centroid merge (cos ≥ 0.92): merge clusters with similar centroids
8. Min cluster size filter (≥ 5 faces)
9. Write `face_cluster` records
10. Auto-annotate: create `student` records, backfill `face_record.student_id`

## 3. Data Cleanup

Execution order (respects FK constraints):

```sql
DELETE FROM emotion_record;
DELETE FROM face_record;
DELETE FROM face_cluster;
DELETE FROM student WHERE student_no LIKE 'auto_%';
DELETE FROM class_image;
```

Qdrant: drop and recreate `face_features_512` collection (512-dim, Cosine distance).

Filesystem: remove all files under `images/cropped/`.

## 4. Configuration

| Parameter | Value | Source |
|-----------|-------|--------|
| Confidence threshold (face) | 0.3 | `config.py` |
| Min face width | 50px | `config.py` |
| Crop margin | 0.30 | `config.py` |
| gRPC timeout | 180s | `config.py` |
| Qdrant batch size | 200 | `config.py` |
| PG batch commit | 100 images | `config.py` |
| Clustering similarity threshold | 0.85 | `config.py` |
| Clustering min_core | 8 | `config.py` |
| Clustering centroid merge | 0.92 | `config.py` |
| Clustering min_cluster_size | 5 | `config.py` |
| Spatial seat distance | 200px | `config.py` |
| gRPC endpoint GPU 0 | `localhost:50053` | `config.py` |
| gRPC endpoint GPU 1 | `localhost:50054` | `config.py` |
| Qdrant URL | `http://localhost:6333` | `config.py` |
| Qdrant collection | `face_features_512` | `config.py` |
| PG host/db/user/pass | env vars / defaults | `config.py` |

## 5. Emotion State Mapping

```
neutral    → ENGAGED
happy      → ENGAGED
surprise   → ENGAGED
sad        → WITHDRAWN
fear       → WITHDRAWN
disgust    → CONFUSED
angry      → CONFUSED
```

## 6. Implementation Notes

- Proto stub generation: `python3 -m grpc_tools.protoc` against `inference.proto`
- Qdrant startup: `docker run -d -p 6333:6333 qdrant/qdrant`
- All 8 emotion dimensions stored in `emotion_record` (not just dominant label)
- Checkpoint: save processed image paths to `/tmp/pipeline_v2_checkpoint.json` every 50 images for resume capability
- bbox stored as JSON: `{"x": float, "y": float, "width": float, "height": float}` — same format as Java pipeline
- Cropped images: `images/cropped/{school}/{class}/{date}/{period}/face_{id}.jpg`
- Qdrant point ID = face_record_id (integer, same as Java clustering expects)
- `face_encoding` in PG stores base64-encoded float32[512] blob for backup/audit
