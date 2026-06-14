#!/usr/bin/env python3
"""Clean up all existing pipeline data from PG + Qdrant + filesystem."""

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
        if count > 0:
            cur.execute(f"DELETE FROM {table} {where}")
            conn.commit()
        log.info("  Deleted %d rows from %s", count, table)

    cur.close()
    conn.close()


def cleanup_qdrant():
    """Drop and recreate Qdrant collection."""
    try:
        r = requests.delete(
            f"{QDRANT_URL}/collections/{QDRANT_COLLECTION}", timeout=10
        )
        log.info("  Qdrant collection '%s' deleted (status=%d)",
                 QDRANT_COLLECTION, r.status_code)
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
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s"
    )
    run_cleanup()
