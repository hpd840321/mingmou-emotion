"""Single-image processing: detect → filter → crop → PG write → Qdrant queue."""

import base64
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
    img_dir = (
        CROP_OUTPUT_ROOT / "cropped" / school / class_name / date_str / period_safe
    )
    img_dir.mkdir(parents=True, exist_ok=True)
    output_path = img_dir / f"face_{face_record_id}.jpg"
    with open(output_path, "wb") as f:
        f.write(crop_bytes)
    return (
        f"/images/cropped/{school}/{class_name}/{date_str}/{period_safe}"
        f"/face_{face_record_id}.jpg"
    )


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
                       face_encoding_b64):
    """Insert face_record row. Returns face_record_id."""
    cur.execute(
        """INSERT INTO face_record
           (class_image_id, bbox, confidence, quality, face_encoding, status)
           VALUES (%s, %s, %s, %s, %s, 'DETECTED')
           RETURNING id""",
        (class_image_id, bbox_json, confidence, quality, face_encoding_b64),
    )
    return cur.fetchone()[0]


def insert_emotion_record(cur, face_record_id, emotion_label, confidence,
                          probs_list):
    """Insert emotion_record with full probability vector."""
    probs = probs_list if probs_list else [None] * 8
    cur.execute(
        """INSERT INTO emotion_record
           (face_record_id, dominant_emotion, dominant_confidence,
            emotion_neutral, emotion_happy, emotion_sad,
            emotion_surprise, emotion_fear, emotion_disgust, emotion_angry,
            emotion_contempt)
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
            probs[7] if len(probs) > 7 else None,
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
    data_root = Path("/media/zebra/data/官渡一中初一班-0526/data")
    try:
        rel_path = image_path.relative_to(data_root)
    except ValueError:
        rel_path = image_path
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

    cur = pg_conn.cursor()
    ci_id, _ = insert_class_image(
        cur, str(image_path), capture_time, period_label, class_id=1
    )

    if not faces:
        update_class_image_counters(cur, ci_id, 0, 0)
        pg_conn.commit()
        cur.close()
        return result

    valid_faces = filter_faces(faces)
    if not valid_faces:
        update_class_image_counters(cur, ci_id, 0, 0)
        pg_conn.commit()
        cur.close()
        return result

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
            face_encoding_b64 = base64.b64encode(
                feature_vec.tobytes()
            ).decode()

        # Insert face_record
        fr_id = insert_face_record(
            cur, ci_id, bbox_json, confidence, quality,
            face_encoding_b64,
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
            emotion_label_cn = get_emotion_label_cn(emotion_label_en)
            insert_emotion_record(
                cur, fr_id, emotion_label_cn, confidence,
                emotion_probs,
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
