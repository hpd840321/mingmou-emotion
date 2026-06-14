"""Thread worker: pulls images from queue, processes, flushes Qdrant in batches."""

import json
import logging
import threading
import time
from pathlib import Path

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
    try:
        with open(CHECKPOINT_FILE, "w") as f:
            json.dump({"processed": sorted(processed_paths)}, f)
    except Exception as e:
        log.warning("Checkpoint save failed: %s", e)


def load_checkpoint():
    """Load checkpoint of processed image paths."""
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
                if stop_event.is_set():
                    break
                continue

            if image_path is None:
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
                result = {
                    "error": str(e), "faces_detected": 0,
                    "emotions_recorded": 0, "qdrant_points": 0,
                    "image_path": str(image_path),
                }

            with stats_lock:
                stats["images_processed"] += 1
                stats["faces_detected"] += result.get("faces_detected", 0)
                stats["emotions_recorded"] += result.get("emotions_recorded", 0)
                if result.get("error"):
                    stats["errors"] += 1
                stats["qdrant_total"] += result.get("qdrant_points", 0)

            processed += 1
            processed_paths.add(path_key)

            if len(qdrant_buffer) >= QDRANT_BATCH_SIZE:
                n = flush_qdrant(qdrant_buffer)
                qdrant_flushed += n

            if processed % DB_COMMIT_INTERVAL == 0:
                save_checkpoint(processed_paths)
                with stats_lock:
                    s = dict(stats)
                log.info(
                    "Worker-%d: %d images, "
                    "total_faces=%d, total_emotions=%d, "
                    "qdrant_flushed=%d, errors=%d",
                    worker_id, processed,
                    s.get("faces_detected", 0),
                    s.get("emotions_recorded", 0),
                    qdrant_flushed,
                    s.get("errors", 0),
                )

            task_queue.task_done()

    finally:
        n = flush_qdrant(qdrant_buffer)
        qdrant_flushed += n
        conn.commit()
        conn.close()
        log.info("Worker-%d: done. %d images, %d qdrant points flushed",
                 worker_id, processed, qdrant_flushed)
