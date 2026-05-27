CREATE TABLE face_cluster (
    id              BIGSERIAL PRIMARY KEY,
    class_id        BIGINT NOT NULL REFERENCES class(id),
    cluster_key     VARCHAR(64) NOT NULL,
    face_tokens     JSONB NOT NULL,
    sample_count    INT NOT NULL DEFAULT 0,
    first_seen_at   TIMESTAMPTZ,
    last_seen_at    TIMESTAMPTZ,
    centroid        REAL[],
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    annotated_by    BIGINT REFERENCES student(id),
    annotated_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_fc_class_status ON face_cluster(class_id, status);
