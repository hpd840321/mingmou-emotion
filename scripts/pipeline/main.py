#!/usr/bin/env python3
"""Main orchestrator: scan → parallel process → cluster."""

import argparse
import logging
import queue
import sys
import threading
import time
from pathlib import Path

import psycopg2

# Ensure project root is importable
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

from scripts.pipeline.config import (
    DATA_ROOT, NUM_WORKERS,
    DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASS,
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
    parser = argparse.ArgumentParser(
        description="End-to-end face processing pipeline"
    )
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
                        rate = (
                            stats["images_processed"] / elapsed * 60
                            if elapsed > 0 else 0
                        )
                        log.info(
                            "PROGRESS: %d images (%.1f/min), "
                            "%d faces, %d emotions, "
                            "%d qdrant, %d errors, "
                            "elapsed %.1f min",
                            stats["images_processed"], rate,
                            stats["faces_detected"],
                            stats["emotions_recorded"],
                            stats["qdrant_total"],
                            stats["errors"],
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
