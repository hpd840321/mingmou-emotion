#!/usr/bin/env python3
"""Step1 PG: Face Detection via gRPC face_server → PostgreSQL"""
import os, sys, json, time, struct, base64, io, subprocess, argparse, logging
import grpc, psycopg2, psycopg2.extras
from PIL import Image
from pathlib import Path
sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import FaceAnalysisRequest
from inference_pb2_grpc import FaceServiceStub

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

FACE_SERVER = 'localhost:50053'
DB = "host=localhost port=5432 dbname=emotion_platform user=emotion password=emotion"
CHECKPOINT = '/tmp/step1_pg_checkpoint.json'
CONF = 0.5      # Confidence threshold
MARGIN = 0.30   # Crop margin
BATCH = 50      # Checkpoint interval
MAX_RETRIES = 3

FEATURES = 0x01 | 0x20  # DETECT | QUALITY (no emotion in step1)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--max', type=int, default=0)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()

    # gRPC
    ch = grpc.insecure_channel(FACE_SERVER,
        options=[('grpc.max_send_message_length', 50*1024*1024),
                 ('grpc.max_receive_message_length', 50*1024*1024)])
    stub = FaceServiceStub(ch)
    log.info('Connected to face_server')

    # DB
    conn = psycopg2.connect(DB)
    cur = conn.cursor(cursor_factory=psycopg2.extras.DictCursor)

    # Load checkpoint
    done = set()
    if args.resume and os.path.exists(CHECKPOINT):
        with open(CHECKPOINT) as f: done = set(json.load(f).get('done',[]))

    # Get PENDING images
    cur.execute("SELECT id, image_url FROM class_image WHERE status='PENDING' ORDER BY id")
    images = cur.fetchall()
    if args.max: images = images[:args.max]
    if done: images = [r for r in images if r['id'] not in done]
    log.info(f'Processing {len(images)} images')

    t0 = time.time(); ci = 0; faces_total = 0
    for row in images:
        ci_id = row['id']; ci += 1
        path = row['image_url']
        if not os.path.exists(path):
            log.warning(f'  [{ci}] #{ci_id} not found: {path[:80]}')
            done.add(ci_id); continue

        with open(path, 'rb') as f: data = f.read()

        # gRPC with retry
        resp = None
        for attempt in range(MAX_RETRIES):
            try:
                resp = stub.Analyze(FaceAnalysisRequest(image_data=data, enabled_features=FEATURES), timeout=180)
                break
            except Exception as e:
                log.warning(f'  [{ci}] #{ci_id} gRPC fail (att {attempt+1}): {str(e)[:80]}')
                if attempt < MAX_RETRIES-1:
                    subprocess.run(['docker','restart','docker-face-1-1'], capture_output=True, timeout=30)
                    time.sleep(10)
                    ch = grpc.insecure_channel(FACE_SERVER,
                        options=[('grpc.max_send_message_length', 50*1024*1024),
                                 ('grpc.max_receive_message_length', 50*1024*1024)])
                    stub = FaceServiceStub(ch)
        if resp is None or not resp.success or len(resp.faces)==0:
            cur.execute("UPDATE class_image SET status='COMPLETED', face_detected_count=0, emotion_recognized_count=0 WHERE id=%s", (ci_id,))
            conn.commit(); done.add(ci_id); continue

        # Process faces
        fc = 0
        for face in resp.faces:
            if face.token.confidence < CONF: continue
            bbox = json.dumps({'x': face.token.x, 'y': face.token.y,
                               'width': face.token.width, 'height': face.token.height})
            q = face.quality if face.quality > 0 else None
            cur.execute(
                "INSERT INTO face_record (class_image_id,bbox,confidence,quality,status,created_at) VALUES (%s,%s,%s,%s,'DETECTED',NOW()) RETURNING id",
                (ci_id, bbox, face.token.confidence, q))
            fr_id = cur.fetchone()[0]
            # Save crop
            mx = max(1, int(face.token.width * MARGIN))
            my = max(1, int(face.token.height * MARGIN))
            try:
                with Image.open(path) as img:
                    crop = img.crop((max(0,int(face.token.x)-mx), max(0,int(face.token.y)-my),
                                     min(img.width,int(face.token.x+face.token.width)+mx),
                                     min(img.height,int(face.token.y+face.token.height)+my)))
                    buf = io.BytesIO(); crop.save(buf, 'JPEG', quality=90)
                    # Save to images/cropped
                    crop_dir = Path('/media/zebra/data/官渡一中初一班-0526/images/cropped')
                    crop_dir.mkdir(parents=True, exist_ok=True)
                    crop_path = crop_dir / f'face_{fr_id}.jpg'
                    with open(crop_path, 'wb') as cf: cf.write(buf.getvalue())
                    cur.execute("UPDATE face_record SET cropped_image_url=%s WHERE id=%s",
                                (str(crop_path.resolve()), fr_id))
            except Exception as e:
                log.debug(f'  Crop #{fr_id}: {e}')
            fc += 1
            faces_total += 1

        cur.execute("UPDATE class_image SET status='COMPLETED', face_detected_count=%s, emotion_recognized_count=0 WHERE id=%s",
                    (fc, ci_id))
        conn.commit()
        done.add(ci_id)
        if fc > 0:
            log.info(f'  [{ci}/{len(images)}] #{ci_id}: {fc} faces')

        if ci % BATCH == 0:
            with open(CHECKPOINT+'.tmp','w') as f: json.dump({'done':sorted(done)}, f)
            os.replace(CHECKPOINT+'.tmp', CHECKPOINT)
            elapsed = time.time()-t0; rate = ci/elapsed if elapsed>0 else 0
            log.info(f'  Checkpoint: {ci}/{len(images)} imgs, {faces_total} faces ({rate:.1f}/s)')

    with open(CHECKPOINT+'.tmp','w') as f: json.dump({'done':sorted(done)}, f)
    os.replace(CHECKPOINT+'.tmp', CHECKPOINT)
    elapsed = time.time()-t0
    log.info(f'\nDone: {ci} imgs, {faces_total} faces in {elapsed/60:.1f}min')
    cur.close(); conn.close()

if __name__ == '__main__':
    main()
