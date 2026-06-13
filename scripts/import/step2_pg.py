#!/usr/bin/env python3
"""Step2 PG: Emotion recognition via face_server gRPC → PostgreSQL"""
import os, sys, json, time, argparse, logging
import grpc, psycopg2, psycopg2.extras
sys.path.insert(0, '/tmp/proto_out')
from inference_pb2 import FaceAnalysisRequest
from inference_pb2_grpc import FaceServiceStub

logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
log = logging.getLogger(__name__)

FACE_SERVER = 'localhost:50053'
DB = "host=localhost port=5432 dbname=emotion_platform user=emotion password=emotion"
CHECKPOINT = '/tmp/step2_pg_checkpoint.json'
BATCH = 100
# FEAT_DETECT | FEAT_QUALITY | FEAT_EMOTION = 0x01 | 0x20 | 0x80
FEATURES = 0x01 | 0x20 | 0x80

# EmotiEffLib 8-class indices
EMOTION_KEYS = ['angry', 'contempt', 'disgust', 'fear', 'happy', 'neutral', 'sad', 'surprise']

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

    # Get face_records without emotion (use cropped image for speed)
    cur.execute("""
        SELECT fr.id, fr.cropped_image_url
        FROM face_record fr
        LEFT JOIN emotion_record er ON er.face_record_id=fr.id
        WHERE er.id IS NULL AND fr.cropped_image_url IS NOT NULL
        ORDER BY fr.id
    """)
    faces = cur.fetchall()
    if args.max: faces = faces[:args.max]
    if done: faces = [r for r in faces if r['id'] not in done]
    log.info(f'Processing {len(faces)} faces')

    t0 = time.time(); processed = 0
    for i, row in enumerate(faces):
        fr_id = row['id']; path = row['cropped_image_url']
        if not os.path.exists(path):
            done.add(fr_id); continue

        with open(path, 'rb') as f: data = f.read()
        try:
            resp = stub.Analyze(FaceAnalysisRequest(image_data=data, enabled_features=FEATURES), timeout=30)
        except Exception as e:
            log.warning(f'  [{i+1}] FR#{fr_id}: gRPC fail: {str(e)[:60]}')
            done.add(fr_id); continue

        if not resp.success or len(resp.faces)==0:
            done.add(fr_id); continue

        face = resp.faces[0]
        emotion = face.emotion
        if not emotion or not emotion.probabilities:
            done.add(fr_id); continue

        probs = list(emotion.probabilities)[:8]
        while len(probs) < 8: probs.append(0.0)

        # Build dict from 8-class indices
        ed = {}
        for k_idx, key in enumerate(EMOTION_KEYS):
            ed[key] = probs[k_idx] if k_idx < len(probs) else 0.0

        # Find dominant
        max_idx = max(range(len(probs)), key=lambda k: probs[k])
        dominant = EMOTION_KEYS[max_idx]
        dominant_conf = probs[max_idx]

        cur.execute("""
            INSERT INTO emotion_record
            (face_record_id, emotion_happy, emotion_sad, emotion_angry, emotion_surprise,
             emotion_fear, emotion_disgust, emotion_neutral, emotion_contempt,
             dominant_emotion, dominant_confidence, created_at)
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW())
        """, (fr_id, ed.get('happy'), ed.get('sad'), ed.get('angry'),
              ed.get('surprise'), ed.get('fear'), ed.get('disgust'),
              ed.get('neutral'), ed.get('contempt'), dominant, dominant_conf))
        cur.execute("UPDATE face_record SET status='IDENTIFIED' WHERE id=%s", (fr_id,))
        processed += 1; done.add(fr_id)

        if processed % BATCH == 0:
            conn.commit()
            with open(CHECKPOINT+'.tmp','w') as f: json.dump({'done':sorted(done)}, f)
            os.replace(CHECKPOINT+'.tmp', CHECKPOINT)
            elapsed = time.time()-t0; rate = (i+1)/elapsed if elapsed>0 else 0
            log.info(f'  Progress: {i+1}/{len(faces)} ({rate:.1f}/s, ETA {(len(faces)-i-1)/rate/60:.0f}min)')

    conn.commit()
    with open(CHECKPOINT+'.tmp','w') as f: json.dump({'done':sorted(done)}, f)
    os.replace(CHECKPOINT+'.tmp', CHECKPOINT)
    elapsed = time.time()-t0
    log.info(f'\nDone: {processed}/{len(faces)} in {elapsed/60:.1f}min')
    cur.close(); conn.close()

if __name__ == '__main__':
    main()
