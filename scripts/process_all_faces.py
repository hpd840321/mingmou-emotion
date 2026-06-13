#!/usr/bin/env python3
"""
全量人脸检测 + 裁剪 + 情绪识别脚本
直连 face_server:50053 (检测) 和 emotion_server:50057 (情绪)

用法:
  python3 process_all_faces.py                         # 完整处理
  python3 process_all_faces.py --resume                 # 断点续传
  python3 process_all_faces.py --max-images 100         # 仅处理前 N 张
"""

import os
import sys
import json
import time
import base64
import argparse
import logging
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock

import grpc
from PIL import Image
import io

sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import FaceAnalysisRequest, FaceAnalysisResponse
from inference_pb2_grpc import FaceServiceStub

# === Config ===
FACE_SERVER = 'localhost:50053'
EMOTION_SERVER = 'localhost:50057'
DATA_ROOT = Path('/home/zebra/Downloads/官渡一中初一班-0526/data')
OUTPUT_ROOT = Path('/home/zebra/Downloads/官渡一中初一班-0526/processed_faces')
OUTPUT_JSON = Path('/home/zebra/Downloads/官渡一中初一班-0526/face_results.json')
CROP_MARGIN = 0.30
CONFIDENCE_THRESHOLD = 0.3
# Feature flags
FEAT_DETECT = 0x01
FEAT_RECOGNITION = 0x02
FEAT_ATTRIBUTE = 0x10
FEAT_QUALITY = 0x20
FEAT_EMOTION = 0x80
# Standard features WITHOUT recognition (faster)
DETECT_FEATURES = FEAT_DETECT | FEAT_QUALITY | FEAT_EMOTION
# Max concurrent gRPC calls
MAX_CONCURRENT = 1  # Sequential: 1 (stable), can increase to 2

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[logging.StreamHandler()]
)
log = logging.getLogger(__name__)


def init_grpc():
    """Initialize gRPC channel and stub for face_server."""
    channel = grpc.insecure_channel(
        FACE_SERVER,
        options=[
            ('grpc.max_send_message_length', 50 * 1024 * 1024),
            ('grpc.max_receive_message_length', 50 * 1024 * 1024),
        ]
    )
    return FaceServiceStub(channel)


def collect_images():
    """Walk data/ directory and collect all .jpg files with metadata."""
    images = []
    for school_dir in sorted(DATA_ROOT.iterdir()):
        if not school_dir.is_dir():
            continue
        school = school_dir.name
        for class_dir in sorted(school_dir.iterdir()):
            if not class_dir.is_dir():
                continue
            class_name = class_dir.name
            for date_dir in sorted(class_dir.iterdir()):
                if not date_dir.is_dir():
                    continue
                date_name = date_dir.name
                for period_dir in sorted(date_dir.iterdir()):
                    if not period_dir.is_dir():
                        continue
                    period = period_dir.name
                    for img_path in sorted(period_dir.glob('*.jpg')):
                        images.append({
                            'path': str(img_path),
                            'school': school,
                            'class': class_name,
                            'date': date_name,
                            'period': period,
                        })
    return images


def load_checkpoint(results_file):
    """Load existing results for resumability."""
    if results_file.exists():
        with open(results_file, 'r') as f:
            data = json.load(f)
        processed = set()
        for item in data.get('faces', []):
            processed.add(item['image_path'])
        return data, processed
    return {'faces': [], 'stats': {'total_images': 0, 'total_faces': 0, 'failed_images': 0}}, set()


def save_checkpoint(results_file, data):
    """Save results to JSON."""
    tmp = str(results_file) + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, results_file)


def crop_face(image_path, bbox, margin=CROP_MARGIN):
    """Crop a face from the original image with margin."""
    x, y, w, h = bbox
    mx = int(w * margin)
    my = int(h * margin)
    with Image.open(image_path) as img:
        left = max(0, int(x) - mx)
        top = max(0, int(y) - my)
        right = min(img.width, int(x + w) + mx)
        bottom = min(img.height, int(y + h) + my)
        crop = img.crop((left, top, right, bottom))
        buf = io.BytesIO()
        crop.save(buf, 'JPEG', quality=90)
        return buf.getvalue()


def process_single_image(args):
    """Process one image: detect → crop for each face. Emotion from Analyze response."""
    face_stub, img_info, output_root = args
    img_path = img_info['path']
    results = []

    try:
        with open(img_path, 'rb') as f:
            image_data = f.read()
    except Exception as e:
        log.warning(f"  Cannot read {img_path}: {e}")
        return results

    # --- Face Detection via gRPC Analyze ---
    try:
        req = FaceAnalysisRequest(
            image_data=image_data,
            enabled_features=DETECT_FEATURES
        )
        resp = face_stub.Analyze(req, timeout=180)
    except Exception as e:
        log.warning(f"  Analyze failed for {Path(img_path).name}: {e}")
        return results

    if not resp.success or len(resp.faces) == 0:
        return results  # No faces detected

    # --- Process each face ---
    for face_idx, face in enumerate(resp.faces):
        token = face.token
        conf = token.confidence
        if conf < CONFIDENCE_THRESHOLD:
            continue

        bbox = (token.x, token.y, token.width, token.height)
        face_result = {
            'image_path': img_path,
            'school': img_info['school'],
            'class': img_info['class'],
            'date': img_info['date'],
            'period': img_info['period'],
            'face_idx': face_idx,
            'bbox': {'x': token.x, 'y': token.y, 'w': token.width, 'h': token.height},
            'confidence': conf,
            'quality': face.quality,
            'emotion_label': None,
            'emotion_probabilities': None,
        }

        # Extract emotion from Analyze response (face_server returns it internally)
        if face.emotion and face.emotion.label:
            face_result['emotion_label'] = face.emotion.label
            face_result['emotion_probabilities'] = list(face.emotion.probabilities)

        # Crop face
        try:
            crop_jpeg = crop_face(img_path, bbox)
            if not crop_jpeg:
                continue

            # Save cropped face
            rel_path = Path(img_path).relative_to(DATA_ROOT)
            crop_dir = output_root / rel_path.parent
            crop_dir.mkdir(parents=True, exist_ok=True)
            crop_filename = f"face_{face_idx}_{Path(img_path).stem}.jpg"
            crop_path = crop_dir / crop_filename
            with open(crop_path, 'wb') as f:
                f.write(crop_jpeg)
            face_result['crop_path'] = str(crop_path)

        except Exception as e:
            log.debug(f"  Crop failed for face {face_idx}: {e}")

        results.append(face_result)

    return results


def main():
    parser = argparse.ArgumentParser(description='Full face detection + cropping + emotion')
    parser.add_argument('--resume', action='store_true', help='Resume from existing results')
    parser.add_argument('--max-images', type=int, default=0, help='Max images to process (0=all)')
    parser.add_argument('--concurrent', type=int, default=MAX_CONCURRENT, help='Concurrent workers')
    args = parser.parse_args()

    # Collect all images
    log.info("Scanning data directory...")
    all_images = collect_images()
    log.info(f"Found {len(all_images)} images")

    # Load checkpoint
    results_data, processed_paths = load_checkpoint(OUTPUT_JSON)
    if args.resume:
        images_to_process = [img for img in all_images if img['path'] not in processed_paths]
        log.info(f"Resume mode: {len(images_to_process)} remaining (already processed {len(processed_paths)})")
    else:
        images_to_process = all_images
        results_data = {'faces': [], 'stats': {'total_images': 0, 'total_faces': 0, 'failed_images': 0}}

    if args.max_images > 0:
        images_to_process = images_to_process[:args.max_images]
        log.info(f"Limited to {args.max_images} images")

    if not images_to_process:
        log.info("Nothing to process")
        return

    # Initialize gRPC
    log.info("Connecting to face_server...")
    face_stub = init_grpc()
    log.info("Connected. Starting processing...")

    # Process images
    start_time = time.time()
    total = len(images_to_process)
    stats = {'detected': 0, 'no_face': 0, 'failed': 0, 'faces': 0, 'emotions': 0}
    result_lock = Lock()
    save_counter = 0

    # Sequential processing (stable for face_server)
    for idx, img_info in enumerate(images_to_process):
        rel_name = Path(img_info['path']).relative_to(DATA_ROOT)
        log.info(f"[{idx+1}/{total}] {rel_name}")

        face_results = process_single_image((face_stub, img_info, OUTPUT_ROOT))

        with result_lock:
            if len(face_results) > 0:
                results_data['faces'].extend(face_results)
                stats['detected'] += 1
                stats['faces'] += len(face_results)
                stats['emotions'] += sum(1 for r in face_results if r['emotion_label'])
            else:
                stats['no_face'] += 1

        # Save checkpoint every 50 images
        save_counter += 1
        if save_counter >= 50:
            with result_lock:
                results_data['stats'] = {
                    'total_images': len(results_data['faces']),
                    'total_faces': stats['faces'],
                    'failed_images': stats.get('failed', 0),
                }
                save_checkpoint(OUTPUT_JSON, results_data)
            save_counter = 0

        # Progress log
        if (idx + 1) % 50 == 0:
            elapsed = time.time() - start_time
            rate = (idx + 1) / elapsed
            remaining = (total - idx - 1) / rate if rate > 0 else 0
            log.info(f"Progress: {idx+1}/{total} ({rate:.1f} img/s, ETA {remaining/60:.0f}min)")
            log.info(f"  Detected: {stats['detected']}, NoFace: {stats['no_face']}, "
                     f"Faces: {stats['faces']}, Emotions: {stats['emotions']}")

    # Final save
    results_data['stats'] = {
        'total_images': len(results_data['faces']),
        'total_faces': stats['faces'],
        'failed_images': stats.get('failed', 0),
        'elapsed_seconds': int(time.time() - start_time),
    }
    save_checkpoint(OUTPUT_JSON, results_data)

    elapsed = time.time() - start_time
    log.info(f"\n=== Complete ===")
    log.info(f"Processed: {total} images in {elapsed/60:.1f} min")
    log.info(f"Detected faces: {stats['faces']} in {stats['detected']} images")
    log.info(f"Emotions recognized: {stats['emotions']}")
    log.info(f"No faces found: {stats['no_face']} images")
    log.info(f"Results saved to: {OUTPUT_JSON}")


if __name__ == '__main__':
    main()
