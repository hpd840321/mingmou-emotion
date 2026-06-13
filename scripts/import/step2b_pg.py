#!/usr/bin/env python3
"""Step2 PG v2: 用原图+bbox匹配做情绪识别 (face_server Analyze → match by bbox)"""
import os, sys, json, time, math, argparse, logging
import grpc, psycopg2, psycopg2.extras
sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import FaceAnalysisRequest
from inference_pb2_grpc import FaceServiceStub

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

FACE_SERVER = 'localhost:50053'
DB = "host=localhost port=5432 dbname=emotion_platform user=emotion password=emotion"
CHECKPOINT = '/tmp/step2b_pg_checkpoint.json'
BATCH = 20
FEATURES = 0x01 | 0x20 | 0x80  # DETECT | QUALITY | EMOTION
EMOTION_KEYS = ['angry', 'contempt', 'disgust', 'fear', 'happy', 'neutral', 'sad', 'surprise']

def center_dist(a, b):
    return math.sqrt((a['cx']-b['cx'])**2 + (a['cy']-b['cy'])**2)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--max', type=int, default=0)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()

    ch = grpc.insecure_channel(FACE_SERVER,
        options=[('grpc.max_send_message_length', 50*1024*1024),
                 ('grpc.max_receive_message_length', 50*1024*1024)])
    stub = FaceServiceStub(ch)
    log.info('Connected to face_server')

    conn = psycopg2.connect(DB)
    cur = conn.cursor(cursor_factory=psycopg2.extras.DictCursor)

    done = set()
    if args.resume and os.path.exists(CHECKPOINT):
        with open(CHECKPOINT) as f: done = set(json.load(f).get('done',[]))

    # Get class_images that have face_records without emotions
    cur.execute("""
        SELECT ci.id, ci.image_url
        FROM class_image ci
        JOIN face_record fr ON fr.class_image_id=ci.id
        LEFT JOIN emotion_record er ON er.face_record_id=fr.id
        WHERE er.id IS NULL
        GROUP BY ci.id, ci.image_url
        ORDER BY ci.id
    """)
    images = cur.fetchall()
    if args.max: images = images[:args.max]
    if done: images = [r for r in images if r['id'] not in done]
    log.info(f'Processing {len(images)} images (those with unprocessed faces)')

    t0 = time.time(); ci = 0; emotions_done = 0
    for row in images:
        ci_id = row['id']; ci += 1; path = row['image_url']
        if not os.path.exists(path):
            done.add(ci_id); continue

        # Get pending face_records for this image
        cur.execute("""
            SELECT fr.id, fr.bbox FROM face_record fr
            LEFT JOIN emotion_record er ON er.face_record_id=fr.id
            WHERE fr.class_image_id=%s AND er.id IS NULL
        """, (ci_id,))
        pending_faces = cur.fetchall()
        if not pending_faces:
            done.add(ci_id); continue

        # Build center lookup
        fr_lookup = {}
        for pf in pending_faces:
            b = json.loads(pf['bbox'])
            fr_lookup[pf['id']] = {'cx': b['x']+b['width']/2, 'cy': b['y']+b['height']/2}

        with open(path, 'rb') as f: data = f.read()
        try:
            resp = stub.Analyze(FaceAnalysisRequest(image_data=data, enabled_features=FEATURES), timeout=180)
        except Exception as e:
            log.warning(f'  [{ci}] CI#{ci_id}: gRPC fail: {str(e)[:60]}')
            done.add(ci_id); continue

        if not resp.success or len(resp.faces)==0:
            done.add(ci_id); continue

        matched = 0
        for face in resp.faces:
            emotion = face.emotion
            if not emotion or not emotion.probabilities: continue
            cx = face.token.x + face.token.width/2
            cy = face.token.y + face.token.height/2

            # Match to nearest pending face_record by center distance
            best_fr, best_dist = None, 50
            for fr_id, fc in fr_lookup.items():
                d = center_dist({'cx':cx,'cy':cy}, fc)
                if d < best_dist:
                    best_dist = d; best_fr = fr_id
            if best_fr is None: continue

            probs = list(emotion.probabilities)[:8]
            while len(probs) < 8: probs.append(0.0)
            ed = {EMOTION_KEYS[i]: probs[i] if i<len(probs) else 0.0 for i in range(8)}
            max_idx = max(range(len(probs)), key=lambda k: probs[k])
            dominant = EMOTION_KEYS[max_idx]
            dominant_conf = probs[max_idx]

            cur.execute("""
                INSERT INTO emotion_record
                (face_record_id, emotion_happy, emotion_sad, emotion_angry, emotion_surprise,
                 emotion_fear, emotion_disgust, emotion_neutral, emotion_contempt,
                 dominant_emotion, dominant_confidence, created_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW())
            """, (best_fr, ed.get('happy'), ed.get('sad'), ed.get('angry'),
                  ed.get('surprise'), ed.get('fear'), ed.get('disgust'),
                  ed.get('neutral'), ed.get('contempt'), dominant, dominant_conf))
            cur.execute("UPDATE face_record SET status='IDENTIFIED' WHERE id=%s", (best_fr,))
            del fr_lookup[best_fr]
            matched += 1
            emotions_done += 1

        conn.commit()
        done.add(ci_id)
        if matched > 0:
            log.info(f'  [{ci}/{len(images)}] CI#{ci_id}: {matched} emotions')

        if ci % BATCH == 0:
            with open(CHECKPOINT+'.tmp','w') as f: json.dump({'done':sorted(done)}, f)
            os.replace(CHECKPOINT+'.tmp', CHECKPOINT)
            elapsed = time.time()-t0; rate = ci/elapsed if elapsed>0 else 0
            log.info(f'  Checkpoint: {ci}/{len(images)} imgs, {emotions_done} emotions ({rate:.1f}/s)')

    with open(CHECKPOINT+'.tmp','w') as f: json.dump({'done':sorted(done)}, f)
    os.replace(CHECKPOINT+'.tmp', CHECKPOINT)
    elapsed = time.time()-t0
    log.info(f'\nDone: {ci} imgs, {emotions_done} emotions in {elapsed/60:.1f}min')
    cur.close(); conn.close()

if __name__ == '__main__':
    main()
