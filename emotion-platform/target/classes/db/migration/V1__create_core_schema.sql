CREATE TABLE grade (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE class (
    id          BIGSERIAL PRIMARY KEY,
    grade_id    BIGINT       NOT NULL REFERENCES grade(id),
    name        VARCHAR(50)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_class_grade ON class(grade_id);

CREATE TABLE student (
    id            BIGSERIAL PRIMARY KEY,
    class_id      BIGINT       NOT NULL REFERENCES class(id),
    student_no    VARCHAR(20)  NOT NULL UNIQUE,
    name          VARCHAR(50)  NOT NULL,
    face_image_id VARCHAR(64),
    status        VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_student_class ON student(class_id);
