# Pipeline Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fragmented Java/Python face processing pipeline with a single Python pipeline that uses dual GPU gRPC face servers for detection/feature extraction, writes to PostgreSQL + Qdrant, and runs numpy-accelerated clustering.

**Architecture:** 6 Python modules in `scripts/pipeline/` — config, gRPC client (dual GPU), single-image processor, thread worker, clustering, main orchestrator. Workers pull from shared image queue via `ThreadPoolExecutor(max_workers=2)`. Clustering runs after all images processed.

**Tech Stack:** Python 3, gRPC (grpcio 1.59.3), numpy, psycopg2, Pillow, requests (Qdrant REST), ThreadPoolExecutor

---

### Task 1: Environment Setup — Proto Generation + Qdrant

**Files:**
- Create: `/tmp/proto_out/inference_pb2.py`, `/tmp/proto_out/inference_pb2_grpc.py`
- Modify: None

- [ ] **Step 1: Generate Python gRPC stubs from inference.proto**

```bash
python3 -m grpc_tools.protoc \
  -I /media/zebra/data/官渡一中初一班-0526/emotion-platform/src/main/proto \
  --python_out=/tmp/proto_out \
  --grpc_python_out=/tmp/proto_out \
  /media/zebra/data/官渡一中初一班-0526/emotion-platform/src/main/proto/inference.proto
```

Expected: Creates `inference_pb2.py` and `inference_pb2_grpc.py` in `/tmp/proto_out/`.

- [ ] **Step 2: Verify stubs import correctly**

```bash
python3 -c "
import sys; sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import FaceAnalysisRequest
from inference_pb2_grpc import FaceServiceStub
print('Proto stubs OK: FaceAnalysisRequest + FaceServiceStub')
"
```

Expected: "Proto stubs OK: FaceAnalysisRequest + FaceServiceStub"

- [ ] **Step 3: Start Qdrant container**

```bash
docker run -d --name qdrant-pipeline \
  -p 6333:6333 \
  -v qdrant_data:/qdrant/storage \
  qdrant/qdrant:latest
```

Expected: Container starts, `curl http://localhost:6333/health` returns 200.

- [ ] **Step 4: Commit proto stubs and Qdrant up check**

```bash
git add -f /tmp/proto_out/inference_pb2.py /tmp/proto_out/inference_pb2_grpc.py 2>/dev/null || echo "stubs in /tmp, not tracked"
echo "Qdrant running: $(curl -s http://localhost:6333/health | python3 -c 'import sys,json; print(json.load(sys.stdin).get(\"title\",\"unknown\"))' 2>/dev/null || echo 'check failed')"
```

---

### Task 2: Configuration Module

**Files:**
- Create: `scripts/pipeline/config.py`

- [ ] **Step 1: Write config.py with all constants**

```python
"""Pipeline configuration — single source of truth for all thresholds, endpoints, paths."""

import os
from pathlib import Path

# ── gRPC face servers ──
GRPC_ENDPOINTS = [
    "localhost:50053",  # GPU 0 (docker-face-1)
    "localhost:50054",  # GPU 1 (docker-face-2)
]
GRPC_TIMEOUT = 180       # seconds
GRPC_MAX_MSG_LENGTH = 50 * 1024 * 1024  # 50 MB

# ── gRPC feature flags ──
# 0xB3 = DETECT(0x01) | FEATURE(0x02) | ATTRIBUTE(0x10) | QUALITY(0x20) | EMOTION(0x80)
ENABLED_FEATURES = 0xB3

# ── Detection filters ──
CONFIDENCE_THRESHOLD = 0.3
MIN_FACE_WIDTH = 50

# ── Cropping ──
CROP_MARGIN = 0.30

# ── Database ──
DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_PORT = int(os.environ.get("DB_PORT", 5432))
DB_NAME = os.environ.get("DB_NAME", "emotion_platform")
DB_USER = os.environ.get("DB_USER", "emotion")
DB_PASS = os.environ.get("DB_PASS", "emotion")

# ── Qdrant ──
QDRANT_URL = "http://localhost:6333"
QDRANT_COLLECTION = "face_features_512"
QDRANT_VECTOR_DIM = 512
QDRANT_BATCH_SIZE = 200

# ── Paths ──
DATA_ROOT = Path(os.environ.get(
    "DATA_ROOT",
    "/media/zebra/data/官渡一中初一班-0526/data"
))
CROP_OUTPUT_ROOT = Path(os.environ.get(
    "CROP_ROOT",
    "/media/zebra/data/官渡一中初一班-0526/emotion-platform/images"
))
CHECKPOINT_FILE = "/tmp/pipeline_v2_checkpoint.json"
PROTO_PATH = "/tmp/proto_out"

# ── Pipeline ──
NUM_WORKERS = 2
DB_COMMIT_INTERVAL = 50   # commit PG every N images

# ── Clustering ──
CLUSTER_SIMILARITY_THRESHOLD = 0.85
CLUSTER_MIN_CORE = 8
CLUSTER_CENTROID_MERGE = 0.92
CLUSTER_MIN_SIZE = 5
CLUSTER_MIN_CONFIDENCE = 0.5
SPATIAL_SEAT_DIST = 200

# ── Emotion labels (index → label) ──
EMOTION_LABELS = [
    "neutral", "happy", "sad", "surprise",
    "fear", "disgust", "angry", "contempt"
]

EMOTION_LABELS_CN = [
    "中性", "开心", "伤心", "惊讶",
    "恐惧", "厌恶", "愤怒", "蔑视"
]

# ── Emotion → dominant state ──
EMOTION_TO_STATE = {
    "neutral": "ENGAGED",
    "happy": "ENGAGED",
    "surprise": "ENGAGED",
    "sad": "WITHDRAWN",
    "fear": "WITHDRAWN",
    "disgust": "CONFUSED",
    "angry": "CONFUSED",
    "contempt": "CONFUSED",
}

# ── School / Class defaults (derived from path convention) ──
SCHOOL_NAME = "官渡一中"
CLASS_NAME = "初一班"
```

- [ ] **Step 2: Verify config imports cleanly**

```bash
python3 -c "from scripts.pipeline.config import *; print(f'Config OK: {len(GRPC_ENDPOINTS)} endpoints, {QDRANT_URL=}')"
```

Expected: "Config OK: 2 endpoints, QDRANT_URL='http://localhost:6333'"

- [ ] **Step 3: Commit**

```bash
git add scripts/pipeline/config.py
git commit -m "feat: add pipeline config module with all constants"
```

---

### Task 3: Data Cleanup Script

**Files:**
- Create: `scripts/pipeline/cleanup.py`

- [ ] **Step 1: Write cleanup.py**

```python
#!/usr/bin/env python3
"""Clean up all existing pipeline data from PG + Qdrant + filesystem."""

import json
import logging
import shutil

import psycopg2
import requests

from scripts.pipeline.config import (
    DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS,
    QDRANT_URL, QDRANT_COLLECTION, QDRANT_VECTOR_DIM,
    CROP_OUTPUT_ROOT,
)

log = logging.getLogger(__name__)


def db_connect():
    return psycopg2.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER,
        password=DB_PASS, database=DB_NAME
    )


def cleanup_database():
    """Delete all pipeline data from PostgreSQL in FK-safe order."""
    conn = db_connect()
    cur = conn.cursor()

    tables = [
        ("emotion_record",     "emotion records"),
        ("face_record",        "face records"),
        ("face_cluster",       "face clusters"),
        ("student",            "auto students", "WHERE student_no LIKE 'auto_%'"),
        ("class_image",        "class images"),
    ]

    for entry in tables:
        table = entry[0]
        label = entry[1]
        where = entry[2] if len(entry) > 2 else ""
        cur.execute(f"SELECT COUNT(*) FROM {table} {where}")
        count = cur.fetchone()[0]
        cur.execute(f"DELETE FROM {table} {where}")
        conn.commit()
        log.info("  Deleted %d rows from %s", count, table)

    cur.close()
    conn.close()


def cleanup_qdrant():
    """Drop and recreate Qdrant collection."""
    try:
        r = requests.delete(f"{QDRANT_URL}/collections/{QDRANT_COLLECTION}", timeout=10)
        log.info("  Qdrant collection '%s' deleted (status=%d)", QDRANT_COLLECTION, r.status_code)
    except Exception as e:
        log.warning("  Qdrant delete failed (may not exist): %s", e)

    payload = {
        "vectors": {
            "size": QDRANT_VECTOR_DIM,
            "distance": "Cosine"
        }
    }
    r = requests.put(
        f"{QDRANT_URL}/collections/{QDRANT_COLLECTION}",
        json=payload, timeout=10
    )
    info = r.json()
    log.info("  Qdrant collection '%s' created: %s", QDRANT_COLLECTION,
             info.get("result", info))


def cleanup_filesystem():
    """Remove all cropped face images."""
    crop_dir = CROP_OUTPUT_ROOT / "cropped"
    if crop_dir.exists():
        shutil.rmtree(crop_dir)
        log.info("  Removed cropped images: %s", crop_dir)
    crop_dir.mkdir(parents=True, exist_ok=True)


def run_cleanup():
    log.info("=== Data Cleanup ===")
    log.info("1. Cleaning PostgreSQL...")
    cleanup_database()
    log.info("2. Cleaning Qdrant...")
    cleanup_qdrant()
    log.info("3. Cleaning filesystem...")
    cleanup_filesystem()
    log.info("=== Cleanup Complete ===")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    run_cleanup()
```

- [ ] **Step 2: Dry-run verify syntax**

```bash
python3 -c "import scripts.pipeline.cleanup; print('cleanup module OK')"
```

- [ ] **Step 3: Commit**

```bash
git add scripts/pipeline/cleanup.py
git commit -m "feat: add data cleanup script for PG + Qdrant + filesystem"
```

---

### Task 4: Dual-Endpoint gRPC Client

**Files:**
- Create: `scripts/pipeline/grpc_client.py`

- [ ] **Step 1: Write grpc_client.py**

```python
"""Dual-endpoint gRPC client pool for face analysis."""

import logging
import struct
import sys

import grpc
import numpy as np

sys.path.insert(0, "/tmp/proto_out")
from inference_pb2 import FaceAnalysisRequest
from inference_pb2_grpc import FaceServiceStub

from scripts.pipeline.config import (
    GRPC_ENDPOINTS, GRPC_TIMEOUT, GRPC_MAX_MSG_LENGTH,
    ENABLED_FEATURES, EMOTION_LABELS,
)

log = logging.getLogger(__name__)


class GrpcClientPool:
    """Pool of gRPC stubs, one per face_server endpoint."""

    def __init__(self):
        self._stubs = []
        self._channels = []
        for endpoint in GRPC_ENDPOINTS:
            channel = grpc.insecure_channel(
                endpoint,
                options=[
                    ("grpc.max_send_message_length", GRPC_MAX_MSG_LENGTH),
                    ("grpc.max_receive_message_length", GRPC_MAX_MSG_LENGTH),
                ],
            )
            stub = FaceServiceStub(channel)
            self._channels.append(channel)
            self._stubs.append(stub)
        log.info("gRPC pool: %d endpoints %s", len(self._stubs), GRPC_ENDPOINTS)

    @property
    def pool_size(self):
        return len(self._stubs)

    def get_stub(self, worker_id):
        """Return stub for given worker (round-robin)."""
        idx = worker_id % len(self._stubs)
        return self._stubs[idx]

    def analyze(self, image_bytes, worker_id):
        """Call FaceService.Analyze. Returns list of face dicts."""
        stub = self.get_stub(worker_id)
        req = FaceAnalysisRequest(
            image_data=image_bytes,
            enabled_features=ENABLED_FEATURES,
        )
        resp = stub.Analyze(req, timeout=GRPC_TIMEOUT)

        if not resp.success:
            log.warning("  gRPC Analyze failed: %s", resp.error_message)
            return []

        faces = []
        for f in resp.faces:
            tok = f.token
            face = {
                "bbox": [int(tok.x), int(tok.y), int(tok.width), int(tok.height)],
                "confidence": tok.confidence,
                "quality": f.quality,
            }

            # Gender: 0=female, 1=male
            if f.HasField("attribute"):
                face["gender"] = f.attribute.gender

            # Emotion
            if f.HasField("emotion"):
                face["emotion_index"] = f.emotion.emotion
                face["emotion_label"] = (
                    EMOTION_LABELS[f.emotion.emotion]
                    if 0 <= f.emotion.emotion < len(EMOTION_LABELS)
                    else "unknown"
                )
                if f.emotion.probabilities:
                    face["emotion_probs"] = list(f.emotion.probabilities)

            # 512-dim feature vector (raw bytes → float32 array)
            if f.feature:
                n_floats = len(f.feature) // 4
                if n_floats == 512:
                    vals = struct.unpack(f"{n_floats}f", f.feature)
                    face["feature"] = np.array(vals, dtype=np.float32)

            faces.append(face)
        return faces

    def close(self):
        for ch in self._channels:
            ch.close()
```

- [ ] **Step 2: Quick smoke test against running face servers**

```bash
python3 -c "
import sys; sys.path.insert(0, '/tmp/proto_out')
from scripts.pipeline.grpc_client import GrpcClientPool
pool = GrpcClientPool()
print(f'Pool size: {pool.pool_size}')
# Test with a small image
import base64
data = base64.b64decode('$(base64 -w0 /media/zebra/data/官渡一中初一班-0526/data/官渡一中/初一班/202505/20250528112500/20250528112500_0001.jpg 2>/dev/null || echo "")')
if data:
    faces = pool.analyze(data, worker_id=0)
    print(f'Test image: {len(faces)} faces detected')
    for f in faces:
        has_feat = f.get('feature') is not None
        print(f'  bbox={f[\"bbox\"]}, conf={f[\"confidence\"]:.3f}, feat={has_feat}, emotion={f.get(\"emotion_label\")}')
else:
    print('Test image not found, skipping smoke test')
pool.close()
" 2>&1
```

Expected: Reports faces detected with features and emotions.

- [ ] **Step 3: Commit**

```bash
git add scripts/pipeline/grpc_client.py
git commit -m "feat: add dual-endpoint gRPC client pool for face analysis"
```

---

### Task 5: Image Processor (Single-Image Logic)

**Files:**
- Create: `scripts/pipeline/processor.py`

- [ ] **Step 1: Write processor.py**

```python
"""Single-image processing: detect → filter → crop → PG write → Qdrant queue."""

import json
import logging
import re
from datetime import datetime
from io import BytesIO
from pathlib import Path

from PIL import Image

from scripts.pipeline.config import (
    CONFIDENCE_THRESHOLD, MIN_FACE_WIDTH, CROP_MARGIN,
    CROP_OUTPUT_ROOT, EMOTION_LABELS_CN, EMOTION_TO_STATE,
    SCHOOL_NAME, CLASS_NAME,
)

log = logging.getLogger(__name__)


def parse_filename_datetime(filename):
    """Extract datetime from YYYYMMDDHHmmss filename prefix."""
    m = re.match(r"(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})", filename)
    if m:
        return datetime(
            int(m.group(1)), int(m.group(2)), int(m.group(3)),
            int(m.group(4)), int(m.group(5)), int(m.group(6)),
        )
    return None


def crop_face(image_bytes, bbox, margin=CROP_MARGIN):
    """Crop face from image bytes. Returns JPEG bytes or None."""
    try:
        x, y, w, h = [int(v) for v in bbox]
        img = Image.open(BytesIO(image_bytes))
        mx = max(1, int(w * margin))
        my = max(1, int(h * margin))
        x1 = max(0, x - mx)
        y1 = max(0, y - my)
        x2 = min(img.width - 1, x + w + mx)
        y2 = min(img.height - 1, y + h + my)
        if x2 <= x1 or y2 <= y1:
            return None
        crop = img.crop((x1, y1, x2, y2))
        buf = BytesIO()
        crop.save(buf, "JPEG", quality=95)
        return buf.getvalue()
    except Exception as e:
        log.warning("  Crop failed: %s", e)
        return None


def save_cropped_face(crop_bytes, school, class_name, date_str, period, face_record_id):
    """Save cropped face JPEG to filesystem. Returns relative URL path."""
    period_safe = re.sub(r'[\\/:*?"<>|]', "_", period) if period else "other"
    img_dir = CROP_OUTPUT_ROOT / "cropped" / school / class_name / date_str / period_safe
    img_dir.mkdir(parents=True, exist_ok=True)
    output_path = img_dir / f"face_{face_record_id}.jpg"
    with open(output_path, "wb") as f:
        f.write(crop_bytes)
    return f"/images/cropped/{school}/{class_name}/{date_str}/{period_safe}/face_{face_record_id}.jpg"


def filter_faces(faces):
    """Filter faces by confidence and minimum width."""
    valid = []
    for face in faces:
        if face.get("confidence", 0) < CONFIDENCE_THRESHOLD:
            continue
        bbox = face.get("bbox", [0, 0, 0, 0])
        if len(bbox) >= 3 and bbox[2] < MIN_FACE_WIDTH:
            continue
        valid.append(face)
    return valid


def make_bbox_json(bbox):
    """Convert bbox list to JSON string matching Java pipeline format."""
    return json.dumps({
        "x": float(bbox[0]),
        "y": float(bbox[1]),
        "width": float(bbox[2]),
        "height": float(bbox[3]),
    })


def insert_class_image(cur, image_url, capture_time, period_label, class_id=1):
    """Insert or get class_image row. Returns (ci_id, is_new)."""
    cur.execute("SELECT id FROM class_image WHERE image_url = %s", (image_url,))
    row = cur.fetchone()
    if row:
        return row[0], False
    if capture_time is None:
        capture_time = datetime.now()
    cur.execute(
        """INSERT INTO class_image
           (class_id, image_url, capture_time, period_label, status, source)
           VALUES (%s, %s, %s, %s, 'COMPLETED', 'auto_scan')
           RETURNING id""",
        (class_id, image_url, capture_time, period_label),
    )
    return cur.fetchone()[0], True


def insert_face_record(cur, class_image_id, bbox_json, confidence, quality,
                       face_encoding_b64, gender):
    """Insert face_record row. Returns face_record_id."""
    cur.execute(
        """INSERT INTO face_record
           (class_image_id, bbox, confidence, quality, face_encoding, gender, status)
           VALUES (%s, %s, %s, %s, %s, %s, 'DETECTED')
           RETURNING id""",
        (class_image_id, bbox_json, confidence, quality, face_encoding_b64, gender),
    )
    return cur.fetchone()[0]


def insert_emotion_record(cur, face_record_id, emotion_label, confidence,
                          probs_list, dominant_state):
    """Insert emotion_record with full probability vector."""
    probs = probs_list if probs_list else [None] * 8
    # Map emotion label to Chinese for dominant_state
    cur.execute(
        """INSERT INTO emotion_record
           (face_record_id, dominant_emotion, dominant_confidence,
            emotion_neutral, emotion_happy, emotion_sad,
            emotion_surprise, emotion_fear, emotion_disgust, emotion_angry,
            dominant_state)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
           RETURNING id""",
        (
            face_record_id, emotion_label, confidence,
            probs[0] if len(probs) > 0 else None,
            probs[1] if len(probs) > 1 else None,
            probs[2] if len(probs) > 2 else None,
            probs[3] if len(probs) > 3 else None,
            probs[4] if len(probs) > 4 else None,
            probs[5] if len(probs) > 5 else None,
            probs[6] if len(probs) > 6 else None,
            dominant_state,
        ),
    )
    return cur.fetchone()[0]


def update_class_image_counters(cur, ci_id, face_count, emotion_count):
    """Update face/emotion counters on class_image."""
    cur.execute(
        """UPDATE class_image
           SET face_detected_count = %s, emotion_recognized_count = %s
           WHERE id = %s""",
        (face_count, emotion_count, ci_id),
    )


def get_emotion_label_cn(emotion_label_en):
    """Convert English emotion label to Chinese."""
    mapping = {
        "neutral": "中性", "happy": "开心", "sad": "伤心",
        "surprise": "惊讶", "fear": "恐惧", "disgust": "厌恶",
        "angry": "愤怒", "contempt": "蔑视",
    }
    return mapping.get(emotion_label_en, "未知")


def map_dominant_state(emotion_label_en):
    """Map emotion label to dominant state."""
    return EMOTION_TO_STATE.get(emotion_label_en, "UNKNOWN")


def process_single_image(image_path, image_bytes, worker_id, grpc_pool,
                         pg_conn, qdrant_buffer):
    """Process one image through the full pipeline.

    Returns:
        dict with keys: image_path, faces_detected, emotions_recorded, error
    """
    result = {
        "image_path": str(image_path),
        "faces_detected": 0,
        "emotions_recorded": 0,
        "error": None,
        "qdrant_points": 0,
    }

    # Parse path metadata
    rel_path = image_path.relative_to(
        Path("/media/zebra/data/官渡一中初一班-0526/data")
    )
    parts = str(rel_path).replace("\\", "/").split("/")
    school = parts[0] if len(parts) >= 1 else SCHOOL_NAME
    class_name = parts[1] if len(parts) >= 2 else CLASS_NAME
    period_label = parts[3] if len(parts) >= 4 else "other"
    filename = parts[-1] if parts else ""

    capture_time = parse_filename_datetime(filename)
    if capture_time is None:
        capture_time = datetime.now()

    # gRPC detection
    try:
        faces = grpc_pool.analyze(image_bytes, worker_id)
    except Exception as e:
        result["error"] = f"gRPC failed: {e}"
        return result

    if not faces:
        # Insert class_image even with no faces
        cur = pg_conn.cursor()
        ci_id, _ = insert_class_image(
            cur, str(image_path), capture_time, period_label, class_id=1
        )
        update_class_image_counters(cur, ci_id, 0, 0)
        pg_conn.commit()
        cur.close()
        return result

    valid_faces = filter_faces(faces)
    if not valid_faces:
        cur = pg_conn.cursor()
        ci_id, _ = insert_class_image(
            cur, str(image_path), capture_time, period_label, class_id=1
        )
        update_class_image_counters(cur, ci_id, 0, 0)
        pg_conn.commit()
        cur.close()
        return result

    cur = pg_conn.cursor()
    ci_id, _ = insert_class_image(
        cur, str(image_path), capture_time, period_label, class_id=1
    )

    face_count = 0
    emotion_count = 0
    date_str = capture_time.strftime("%Y-%m-%d") if capture_time else "unknown"

    for face in valid_faces:
        bbox = face["bbox"]
        confidence = face.get("confidence", 0)
        quality = face.get("quality", 0)
        feature_vec = face.get("feature")
        emotion_label_en = face.get("emotion_label")
        emotion_probs = face.get("emotion_probs")
        gender = face.get("gender")

        bbox_json = make_bbox_json(bbox)

        # Encode feature to base64 for PG storage
        face_encoding_b64 = None
        if feature_vec is not None:
            face_encoding_b64 = __import__("base64").b64encode(
                feature_vec.tobytes()
            ).decode()

        # Insert face_record
        fr_id = insert_face_record(
            cur, ci_id, bbox_json, confidence, quality,
            face_encoding_b64, gender,
        )
        face_count += 1

        # Crop + save
        crop_bytes = crop_face(image_bytes, bbox)
        if crop_bytes:
            crop_url = save_cropped_face(
                crop_bytes, school, class_name, date_str, period_label, fr_id
            )
            cur.execute(
                "UPDATE face_record SET cropped_image_url = %s WHERE id = %s",
                (crop_url, fr_id),
            )

        # Insert emotion_record
        if emotion_label_en:
            dominant_state = map_dominant_state(emotion_label_en)
            emotion_label_cn = get_emotion_label_cn(emotion_label_en)
            insert_emotion_record(
                cur, fr_id, emotion_label_cn, confidence,
                emotion_probs, dominant_state,
            )
            emotion_count += 1

        # Queue Qdrant point
        if feature_vec is not None:
            qdrant_buffer.append({
                "id": fr_id,
                "vector": feature_vec.tolist(),
                "payload": {
                    "face_record_id": fr_id,
                    "class_image_id": ci_id,
                },
            })
            result["qdrant_points"] += 1

    update_class_image_counters(cur, ci_id, face_count, emotion_count)
    pg_conn.commit()
    cur.close()

    result["faces_detected"] = face_count
    result["emotions_recorded"] = emotion_count
    return result
```

- [ ] **Step 2: Verify module imports**

```bash
python3 -c "from scripts.pipeline.processor import filter_faces, make_bbox_json; print('processor OK')"
```

- [ ] **Step 3: Commit**

```bash
git add scripts/pipeline/processor.py
git commit -m "feat: add single-image processor with detect/crop/PG/Qdrant"
```

---

### Task 6: Worker Thread + Qdrant Batcher

**Files:**
- Create: `scripts/pipeline/worker.py`

- [ ] **Step 1: Write worker.py**

```python
"""Thread worker: pulls images from queue, processes, flushes Qdrant in batches."""

import logging
import threading
import time

import psycopg2
import requests

from scripts.pipeline.config import (
    DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS,
    QDRANT_URL, QDRANT_COLLECTION, QDRANT_BATCH_SIZE,
    DB_COMMIT_INTERVAL, CHECKPOINT_FILE,
)
from scripts.pipeline.processor import process_single_image

log = logging.getLogger(__name__)


def flush_qdrant(batch):
    """Push accumulated points to Qdrant."""
    if not batch:
        return 0
    try:
        resp = requests.put(
            f"{QDRANT_URL}/collections/{QDRANT_COLLECTION}/points?wait=true",
            json={"points": batch},
            timeout=30,
        )
        if resp.status_code != 200:
            log.error("Qdrant flush failed: %s", resp.text[:200])
            return 0
        n = len(batch)
        batch.clear()
        return n
    except Exception as e:
        log.error("Qdrant flush error: %s", e)
        return 0


def save_checkpoint(processed_paths):
    """Save checkpoint of processed image paths."""
    import json
    try:
        with open(CHECKPOINT_FILE, "w") as f:
            json.dump({"processed": sorted(processed_paths)}, f)
    except Exception as e:
        log.warning("Checkpoint save failed: %s", e)


def load_checkpoint():
    """Load checkpoint of processed image paths."""
    import json
    from pathlib import Path
    try:
        if Path(CHECKPOINT_FILE).exists():
            with open(CHECKPOINT_FILE) as f:
                data = json.load(f)
                return set(data.get("processed", []))
    except Exception:
        pass
    return set()


def run_worker(worker_id, task_queue, grpc_pool, stats, stats_lock,
               stop_event, checkpoint_event):
    """Worker thread entry point.

    Args:
        worker_id: integer 0 or 1
        task_queue: queue.Queue of (image_path, image_bytes) tuples
        grpc_pool: GrpcClientPool instance
        stats: shared dict for accumulating statistics
        stats_lock: threading.Lock for stats
        stop_event: threading.Event to signal stop
        checkpoint_event: threading.Event to trigger checkpoint save
    """
    conn = psycopg2.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER,
        password=DB_PASS, database=DB_NAME,
    )
    qdrant_buffer = []
    processed = 0
    qdrant_flushed = 0
    processed_paths = set()

    log.info("Worker-%d: started, PG connected", worker_id)

    try:
        while not stop_event.is_set():
            try:
                image_path, image_bytes = task_queue.get(timeout=5)
            except Exception:
                # Queue empty or timeout — check stop
                if stop_event.is_set():
                    break
                continue

            if image_path is None:
                # Sentinel — stop signal
                break

            path_key = str(image_path)
            try:
                result = process_single_image(
                    image_path, image_bytes, worker_id, grpc_pool,
                    conn, qdrant_buffer,
                )
            except Exception as e:
                log.error("Worker-%d: process error for %s: %s",
                          worker_id, path_key, e)
                result = {"error": str(e), "faces_detected": 0,
                          "emotions_recorded": 0, "qdrant_points": 0,
                          "image_path": str(image_path)}

            # Update shared stats
            with stats_lock:
                stats["images_processed"] += 1
                stats["faces_detected"] += result.get("faces_detected", 0)
                stats["emotions_recorded"] += result.get("emotions_recorded", 0)
                stats["errors"] += 1 if result.get("error") else 0
                stats["qdrant_total"] += result.get("qdrant_points", 0)

            processed += 1
            processed_paths.add(path_key)

            # Flush Qdrant batch
            if len(qdrant_buffer) >= QDRANT_BATCH_SIZE:
                n = flush_qdrant(qdrant_buffer)
                qdrant_flushed += n

            # Periodic checkpoint
            if processed % DB_COMMIT_INTERVAL == 0:
                save_checkpoint(processed_paths)
                total_stats = dict(stats)  # copy under lock for logging
                log.info(
                    "Worker-%d: %d images, faces=%d, emotions=%d, qdrant=%d, errors=%d",
                    worker_id, processed,
                    result.get("faces_detected", 0),
                    result.get("emotions_recorded", 0),
                    qdrant_flushed,
                    stats.get("errors", 0),
                )

            task_queue.task_done()

    finally:
        # Final flush
        n = flush_qdrant(qdrant_buffer)
        qdrant_flushed += n
        conn.commit()
        conn.close()
        log.info("Worker-%d: done. %d images, %d qdrant points flushed",
                 worker_id, processed, qdrant_flushed)
```

- [ ] **Step 2: Verify syntax**

```bash
python3 -c "from scripts.pipeline.worker import run_worker; print('worker OK')"
```

- [ ] **Step 3: Commit**

```bash
git add scripts/pipeline/worker.py
git commit -m "feat: add thread worker with PG pool and Qdrant batching"
```

---

### Task 7: Clustering Module (Ported from Java)

**Files:**
- Create: `scripts/pipeline/clustering.py`

- [ ] **Step 1: Write clustering.py**

```python
"""Feature clustering: Qdrant scroll → numpy cosine graph → core-expansion DBSCAN.

Ported from FaceClusteringServiceV2.java with numpy acceleration.
"""

import json
import logging
import re
from collections import defaultdict
from datetime import datetime
from pathlib import Path

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
        centroid = None
        vecs_in_cluster = [vectors[i] for i in cluster_core_idx if vectors[i] is not None]
        if vecs_in_cluster:
            centroid = np.mean(vecs_in_cluster, axis=0)

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
                sim = float(np.dot(centroid, vectors[cand]) /
                          (np.linalg.norm(centroid) * np.linalg.norm(vectors[cand]) + 1e-10))
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
                sim = float(np.dot(centroids[i], centroids[j]) /
                          (np.linalg.norm(centroids[i]) * np.linalg.norm(centroids[j]) + 1e-10))
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
             sum(1 for c in merged if len(c) > max(len(clusters[i]) for i in range(len(clusters)) if i in comp)),
             len(clusters), len(merged))
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
        if meta["confidence"] is not None and meta["confidence"] < CLUSTER_MIN_CONFIDENCE:
            filter_stats["conf"] += 1
            continue
        if meta["face_width"] < MIN_FACE_WIDTH:
            filter_stats["size"] += 1
            continue
        filtered_indices.append(i)
        class_ids.append(meta["class_id"])

    n = len(filtered_indices)
    log.info("After filtering: %d remained (conf<%s: %d, w<%s: %d, noMeta: %d)",
             n, CLUSTER_MIN_CONFIDENCE, filter_stats["conf"],
             MIN_FACE_WIDTH, filter_stats["size"], filter_stats["no_meta"])

    if n < 2:
        log.warning("Insufficient points after filtering (n=%d)", n)
        return {"clusters": 0, "total_faces": len(raw_points), "outliers": len(raw_points)}

    # 5. Build similarity graph (vectorized)
    sub_vectors = np.array([vectors_list[i] for i in filtered_indices], dtype=np.float32)
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
        cid = derive_class_id([filtered_indices[idx] for idx in cluster], class_ids)

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
    """Create student records for pending clusters and backfill face_record.student_id."""
    cur.execute("SELECT id, class_id, face_tokens, student_id FROM face_cluster WHERE status = 'pending'")
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

        # Count existing auto students for this class
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
                        "UPDATE face_record SET student_id = %s WHERE id = %s AND student_id IS NULL",
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
                        "UPDATE face_record SET student_id = %s WHERE id = %s AND student_id IS NULL",
                        (student_id, fr_id),
                    )
                except (ValueError, TypeError):
                    pass

        cur.execute(
            "UPDATE face_cluster SET student_id = %s, status = 'auto_annotated' WHERE id = %s",
            (student_id, cid),
        )

    conn.commit()
    log.info("Auto-annotation complete")


if __name__ == "__main__":
    import time
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
    result = run_clustering()
    print(json.dumps(result, indent=2, default=str))
```

- [ ] **Step 2: Verify syntax**

```bash
python3 -c "from scripts.pipeline.clustering import cosine_similarity_matrix, bfs_clusters; print('clustering OK')"
```

- [ ] **Step 3: Commit**

```bash
git add scripts/pipeline/clustering.py
git commit -m "feat: add numpy-accelerated clustering ported from Java"
```

---

### Task 8: Main Orchestrator

**Files:**
- Create: `scripts/pipeline/main.py`

- [ ] **Step 1: Write main.py**

```python
#!/usr/bin/env python3
"""Main orchestrator: scan → parallel process → cluster."""

import argparse
import logging
import os
import queue
import sys
import threading
import time
from datetime import datetime
from pathlib import Path

import psycopg2

# Ensure scripts/pipeline is importable
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from scripts.pipeline.config import (
    DATA_ROOT, NUM_WORKERS, DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS,
    CHECKPOINT_FILE,
)
from scripts.pipeline.grpc_client import GrpcClientPool
from scripts.pipeline.worker import run_worker, load_checkpoint
from scripts.pipeline.cleanup import run_cleanup
from scripts.pipeline.clustering import run_clustering

log = logging.getLogger(__name__)


def ensure_seed_data():
    """Ensure grade and class seed rows exist."""
    conn = psycopg2.connect(
        host=DB_HOST, port=DB_PORT, user=DB_USER,
        password=DB_PASS, database=DB_NAME,
    )
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO grade (id, name, sort_order) VALUES (1, '初一', 1) "
        "ON CONFLICT (id) DO NOTHING"
    )
    cur.execute(
        "INSERT INTO class (id, grade_id, name, sort_order) "
        "VALUES (1, 1, '初一班', 1) ON CONFLICT (id) DO NOTHING"
    )
    conn.commit()
    cur.close()
    conn.close()


def scan_images(data_root, resume=True):
    """Scan data directory for JPEG images. Returns list of Path objects."""
    all_images = sorted(data_root.rglob("*.jpg"))
    log.info("Found %d JPEG images in %s", len(all_images), data_root)

    if resume:
        processed = load_checkpoint()
        remaining = [p for p in all_images if str(p) not in processed]
        log.info("Resume: %d already processed, %d remaining",
                 len(processed), len(remaining))
        return remaining

    return all_images


def main():
    parser = argparse.ArgumentParser(description="End-to-end face processing pipeline")
    parser.add_argument("--skip-cleanup", action="store_true",
                        help="Skip data cleanup step")
    parser.add_argument("--skip-process", action="store_true",
                        help="Skip image processing, run clustering only")
    parser.add_argument("--skip-cluster", action="store_true",
                        help="Skip clustering, process images only")
    parser.add_argument("--max-images", type=int, default=0,
                        help="Limit number of images to process")
    parser.add_argument("--no-resume", action="store_true",
                        help="Do not resume from checkpoint")
    parser.add_argument("--dry-run", action="store_true",
                        help="Scan and report without processing")
    args = parser.parse_args()

    # ── Step 1: Cleanup ──
    if not args.skip_cleanup:
        run_cleanup()

    if args.dry_run:
        images = scan_images(DATA_ROOT, resume=not args.no_resume)
        log.info("Dry run: %d images to process", len(images))
        return

    # ── Step 2: Seed data ──
    ensure_seed_data()

    # ── Step 3: Process images ──
    if not args.skip_process:
        images = scan_images(DATA_ROOT, resume=not args.no_resume)
        if args.max_images > 0:
            images = images[:args.max_images]

        if not images:
            log.warning("No images to process")
        else:
            log.info("=== Processing %d images with %d workers ===",
                     len(images), NUM_WORKERS)

            # Build task queue
            task_queue = queue.Queue()
            for img_path in images:
                try:
                    with open(img_path, "rb") as f:
                        data = f.read()
                    task_queue.put((img_path, data))
                except Exception as e:
                    log.error("Cannot read %s: %s", img_path, e)

            # Send sentinel for each worker
            for _ in range(NUM_WORKERS):
                task_queue.put((None, None))

            # Shared state
            stats = {
                "images_processed": 0,
                "faces_detected": 0,
                "emotions_recorded": 0,
                "errors": 0,
                "qdrant_total": 0,
            }
            stats_lock = threading.Lock()
            stop_event = threading.Event()
            checkpoint_event = threading.Event()

            # gRPC pool
            grpc_pool = GrpcClientPool()

            # Start workers
            workers = []
            start_time = time.time()
            for wid in range(NUM_WORKERS):
                t = threading.Thread(
                    target=run_worker,
                    args=(wid, task_queue, grpc_pool, stats, stats_lock,
                          stop_event, checkpoint_event),
                    daemon=True,
                )
                t.start()
                workers.append(t)

            # Monitor progress
            try:
                while any(t.is_alive() for t in workers):
                    time.sleep(30)
                    with stats_lock:
                        elapsed = time.time() - start_time
                        rate = stats["images_processed"] / elapsed * 60 if elapsed > 0 else 0
                        log.info(
                            "PROGRESS: %d images (%.1f/min), %d faces, %d emotions, "
                            "%d qdrant, %d errors, elapsed %.1f min",
                            stats["images_processed"], rate,
                            stats["faces_detected"], stats["emotions_recorded"],
                            stats["qdrant_total"], stats["errors"],
                            elapsed / 60,
                        )
            except KeyboardInterrupt:
                log.warning("Keyboard interrupt, stopping workers...")
                stop_event.set()

            for t in workers:
                t.join(timeout=60)
            grpc_pool.close()

            elapsed = time.time() - start_time
            log.info("=== Processing Complete ===")
            log.info("  Images: %d", stats["images_processed"])
            log.info("  Faces: %d", stats["faces_detected"])
            log.info("  Emotions: %d", stats["emotions_recorded"])
            log.info("  Qdrant points: %d", stats["qdrant_total"])
            log.info("  Errors: %d", stats["errors"])
            log.info("  Time: %.1f min", elapsed / 60)

    # ── Step 4: Clustering ──
    if not args.skip_cluster:
        log.info("=== Starting Clustering ===")
        result = run_clustering()
        log.info("Clustering result: %s", result)


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
    )
    main()
```

- [ ] **Step 2: Verify syntax**

```bash
python3 -c "from scripts.pipeline.main import main; print('main OK')"
```

- [ ] **Step 3: Commit**

```bash
git add scripts/pipeline/main.py
git commit -m "feat: add main orchestrator with parallel workers + clustering"
```

---

### Task 9: Execute and Verify

**Files:** None (execution only)

- [ ] **Step 1: Dry-run test — scan only**

```bash
python3 scripts/pipeline/main.py --skip-cleanup --skip-process --skip-cluster --dry-run
```

Expected: Reports number of images found in data/.

- [ ] **Step 2: Run cleanup + process first 10 images as smoke test**

```bash
python3 scripts/pipeline/main.py --max-images 10 --skip-cluster
```

Expected: Cleans DB, processes 10 images, reports faces/emotions/qdrant stats. Check:
```bash
python3 -c "
import psycopg2
conn = psycopg2.connect(host='localhost', user='emotion', password='emotion', database='emotion_platform')
cur = conn.cursor()
cur.execute('SELECT COUNT(*) FROM face_record')
print(f'face_record: {cur.fetchone()[0]}')
cur.execute('SELECT COUNT(*) FROM emotion_record')
print(f'emotion_record: {cur.fetchone()[0]}')
cur.execute('SELECT COUNT(*) FROM class_image')
print(f'class_image: {cur.fetchone()[0]}')
cur.close(); conn.close()
"
```

- [ ] **Step 3: Test clustering on processed data**

```bash
python3 scripts/pipeline/main.py --skip-cleanup --skip-process
```

Expected: Scrolls Qdrant, runs clustering, creates face_cluster + student records.

- [ ] **Step 4: Full pipeline run**

```bash
python3 scripts/pipeline/main.py
```

Expected: Full cleanup → 4560 image processing (dual GPU, ~1 hour) → clustering.

- [ ] **Step 5: Verify end-to-end results**

```bash
python3 -c "
import psycopg2
conn = psycopg2.connect(host='localhost', user='emotion', password='emotion', database='emotion_platform')
cur = conn.cursor()
cur.execute('SELECT COUNT(*), status FROM class_image GROUP BY status')
for row in cur.fetchall(): print(f'class_image: {row[0]} ({row[1]})')
cur.execute('SELECT COUNT(*) FROM face_record')
print(f'face_record: {cur.fetchone()[0]}')
cur.execute('SELECT COUNT(*) FROM emotion_record')
print(f'emotion_record: {cur.fetchone()[0]}')
cur.execute('SELECT COUNT(*) FROM face_cluster')
print(f'face_cluster: {cur.fetchone()[0]}')
cur.execute('SELECT COUNT(*) FROM student WHERE student_no LIKE \'auto_%\'')
print(f'auto students: {cur.fetchone()[0]}')
cur.close(); conn.close()
"
import requests; r = requests.get('http://localhost:6333/collections/face_features_512')
print(f'Qdrant points: {r.json()[\"result\"][\"points_count\"]}')
"
```

Expected: All class_image = COMPLETED, face_record > 10000, face_cluster > 40, auto students > 40, Qdrant points matches face_records with features.
```

- [ ] **Step 6: Commit final state**

```bash
git add scripts/pipeline/ && git commit -m "chore: finalize pipeline modules after verification"
```
