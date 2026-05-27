CREATE TABLE IF NOT EXISTS class_period (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    period_key  VARCHAR(20) NOT NULL UNIQUE,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0
);

INSERT INTO class_period (name, period_key, start_time, end_time, sort_order) VALUES
    ('早读-到校', 'arrival', '06:00', '07:40', 1),
    ('第1节', 'period_1', '07:40', '08:20', 2),
    ('第2节', 'period_2', '08:20', '09:00', 3),
    ('第3节', 'period_3', '09:00', '09:50', 4),
    ('课间操', 'recess', '09:50', '10:10', 5),
    ('第4节', 'period_4', '10:10', '10:50', 6),
    ('第5节', 'period_5', '10:50', '11:40', 7),
    ('午餐-午休', 'lunch', '11:40', '14:00', 8),
    ('第6节', 'period_6', '14:00', '14:40', 9),
    ('第7节', 'period_7', '14:40', '15:30', 10),
    ('第8节', 'period_8', '15:30', '16:20', 11),
    ('课外活动-放学', 'afterclass', '16:20', '19:00', 12)
ON CONFLICT (period_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS emotion_aggregation (
    id                  BIGSERIAL PRIMARY KEY,
    student_id          BIGINT NOT NULL REFERENCES student(id),
    class_id            BIGINT NOT NULL REFERENCES class(id),
    date                DATE NOT NULL,
    period_id           BIGINT REFERENCES class_period(id),
    sample_count        INT NOT NULL DEFAULT 0,
    ratio_happy         REAL,
    ratio_sad           REAL,
    ratio_angry         REAL,
    ratio_surprise      REAL,
    ratio_fear          REAL,
    ratio_disgust       REAL,
    ratio_neutral       REAL,
    engagement_score    REAL,
    positive_ratio      REAL,
    negative_ratio      REAL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ea_unique ON emotion_aggregation(student_id, date, period_id);

CREATE TABLE IF NOT EXISTS alert_rule (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    scope           VARCHAR(20) NOT NULL DEFAULT 'global',
    scope_id        BIGINT,
    metric          VARCHAR(50) NOT NULL,
    operator        VARCHAR(10) NOT NULL,
    threshold       REAL NOT NULL,
    duration_min    INT,
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS alert_log (
    id              BIGSERIAL PRIMARY KEY,
    alert_rule_id   BIGINT NOT NULL REFERENCES alert_rule(id),
    student_id      BIGINT REFERENCES student(id),
    class_id        BIGINT REFERENCES class(id),
    trigger_value   REAL,
    message         TEXT,
    acknowledged    BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_al_student ON alert_log(student_id);
CREATE INDEX IF NOT EXISTS idx_al_ack ON alert_log(acknowledged);

CREATE TABLE IF NOT EXISTS intervention_log (
    id              BIGSERIAL PRIMARY KEY,
    student_id      BIGINT NOT NULL REFERENCES student(id),
    teacher_name    VARCHAR(50),
    action_type     VARCHAR(50) NOT NULL,
    description     TEXT,
    effect          VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_il_student ON intervention_log(student_id);
