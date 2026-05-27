CREATE TABLE class_image (
    id              BIGSERIAL PRIMARY KEY,
    class_id        BIGINT       NOT NULL REFERENCES class(id),
    image_url       TEXT         NOT NULL,
    capture_time    TIMESTAMPTZ  NOT NULL,
    period_label    VARCHAR(20),
    source          VARCHAR(50)  DEFAULT 'third_party',
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ci_class_time ON class_image(class_id, capture_time);
CREATE INDEX idx_ci_status     ON class_image(status);
