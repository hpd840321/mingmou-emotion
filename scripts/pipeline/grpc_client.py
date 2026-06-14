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
