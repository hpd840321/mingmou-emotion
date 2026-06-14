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
