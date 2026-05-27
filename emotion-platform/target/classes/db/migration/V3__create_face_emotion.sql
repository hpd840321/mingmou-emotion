CREATE TABLE face_record (
    id              BIGSERIAL PRIMARY KEY,
    class_image_id  BIGINT       NOT NULL REFERENCES class_image(id),
    student_id      BIGINT       REFERENCES student(id),
    bbox            JSONB,
    face_encoding   JSONB,
    confidence      REAL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'detected',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fr_image   ON face_record(class_image_id);
CREATE INDEX idx_fr_student ON face_record(student_id);

CREATE TABLE emotion_record (
    id                  BIGSERIAL PRIMARY KEY,
    face_record_id      BIGINT       NOT NULL UNIQUE REFERENCES face_record(id),
    emotion_happy       REAL,
    emotion_sad         REAL,
    emotion_angry       REAL,
    emotion_surprise    REAL,
    emotion_fear        REAL,
    emotion_disgust     REAL,
    emotion_neutral     REAL,
    dominant_emotion    VARCHAR(20)  NOT NULL,
    dominant_confidence REAL         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_er_dominant   ON emotion_record(dominant_emotion);
CREATE INDEX idx_er_face_record ON emotion_record(face_record_id);
