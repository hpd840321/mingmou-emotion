-- ============================================================
-- 明眸情绪感知平台 (MingMou Emotion Sensing Platform)
-- 完整数据库 DDL 脚本
-- 数据库: mingmou_emotion
-- ============================================================

-- 建库（需超级权限执行）
-- CREATE DATABASE mingmou_emotion OWNER craftlabs;
-- \c mingmou_emotion

-- ============================================================
-- Part 1: 平台基础框架表
-- ============================================================

-- 1.1 系统用户
CREATE TABLE sys_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50) NOT NULL UNIQUE,
    password_hash   VARCHAR(256) NOT NULL,
    real_name       VARCHAR(50) NOT NULL,
    phone           VARCHAR(20),
    email           VARCHAR(100),
    avatar_url      VARCHAR(256),
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    bind_type       VARCHAR(20),                 -- grade / class / school
    bind_id         BIGINT,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_su_username ON sys_user(username);
CREATE INDEX idx_su_bind   ON sys_user(bind_type, bind_id);

-- 1.2 角色
CREATE TABLE sys_role (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(50) NOT NULL UNIQUE,
    name            VARCHAR(50) NOT NULL,
    description     VARCHAR(200),
    sort_order      INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 1.3 用户-角色关联
CREATE TABLE sys_user_role (
    user_id     BIGINT NOT NULL REFERENCES sys_user(id),
    role_id     BIGINT NOT NULL REFERENCES sys_role(id),
    PRIMARY KEY (user_id, role_id)
);

-- 1.4 菜单树
CREATE TABLE sys_menu (
    id              BIGSERIAL PRIMARY KEY,
    parent_id       BIGINT REFERENCES sys_menu(id),
    name            VARCHAR(50) NOT NULL,
    permission_code VARCHAR(100),
    path            VARCHAR(200),
    icon            VARCHAR(50),
    sort_order      INT NOT NULL DEFAULT 0,
    menu_type       VARCHAR(20) NOT NULL DEFAULT 'menu',
    visible         BOOLEAN NOT NULL DEFAULT true,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 1.5 角色-菜单权限
CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL REFERENCES sys_role(id),
    menu_id BIGINT NOT NULL REFERENCES sys_menu(id),
    PRIMARY KEY (role_id, menu_id)
);

-- 1.6 字典类型
CREATE TABLE sys_dict_type (
    id              BIGSERIAL PRIMARY KEY,
    dict_code       VARCHAR(50) NOT NULL UNIQUE,
    dict_name       VARCHAR(100) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 1.7 字典数据
CREATE TABLE sys_dict_data (
    id              BIGSERIAL PRIMARY KEY,
    dict_type_id    BIGINT NOT NULL REFERENCES sys_dict_type(id),
    item_code       VARCHAR(50) NOT NULL,
    item_value      VARCHAR(100) NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sdd_type ON sys_dict_data(dict_type_id);

-- 1.8 系统配置
CREATE TABLE sys_config (
    id              BIGSERIAL PRIMARY KEY,
    config_key      VARCHAR(100) NOT NULL UNIQUE,
    config_value    TEXT NOT NULL,
    description     VARCHAR(200),
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 1.9 操作日志
CREATE TABLE sys_operation_log (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES sys_user(id),
    username        VARCHAR(50),
    operation_type  VARCHAR(50) NOT NULL,
    target_type     VARCHAR(50),
    target_id       BIGINT,
    description     TEXT,
    request_ip      VARCHAR(50),
    user_agent      VARCHAR(500),
    duration_ms     INT,
    result          VARCHAR(20) NOT NULL DEFAULT 'success',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sol_user ON sys_operation_log(user_id);
CREATE INDEX idx_sol_type ON sys_operation_log(operation_type);
CREATE INDEX idx_sol_time ON sys_operation_log(created_at);

-- 1.10 登录日志
CREATE TABLE sys_login_log (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50) NOT NULL,
    login_type      VARCHAR(20) NOT NULL DEFAULT 'password',
    request_ip      VARCHAR(50),
    result          VARCHAR(20) NOT NULL,
    fail_reason     VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sll_user ON sys_login_log(username);
CREATE INDEX idx_sll_time ON sys_login_log(created_at);


-- ============================================================
-- Part 2: 业务核心表（基于VisionMind外部API）
-- ============================================================

-- 2.1 年级
CREATE TABLE grade (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2.2 班级
CREATE TABLE class (
    id          BIGSERIAL PRIMARY KEY,
    grade_id    BIGINT NOT NULL REFERENCES grade(id),
    name        VARCHAR(50) NOT NULL,
    vm_lib_id   VARCHAR(64),                -- VisionMind FaceLibrary.id
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_class_grade ON class(grade_id);

-- 2.3 学生
CREATE TABLE student (
    id              BIGSERIAL PRIMARY KEY,
    class_id        BIGINT NOT NULL REFERENCES class(id),
    student_no      VARCHAR(20) NOT NULL UNIQUE,
    name            VARCHAR(50) NOT NULL,
    vm_face_id      VARCHAR(64),             -- VisionMind FaceEntry.id
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_student_class ON student(class_id);

-- 2.4 课时段字典表
CREATE TABLE class_period (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    period_key  VARCHAR(20) NOT NULL UNIQUE,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0
);

-- 2.5 课堂图片记录
CREATE TABLE class_image (
    id                  BIGSERIAL PRIMARY KEY,
    class_id            BIGINT NOT NULL REFERENCES class(id),
    image_url           TEXT NOT NULL,
    capture_time        TIMESTAMPTZ NOT NULL,
    period_id           BIGINT REFERENCES class_period(id),
    source              VARCHAR(50) DEFAULT 'third_party',
    vm_analyze_status   VARCHAR(20) DEFAULT 'pending',
    vm_task_id          VARCHAR(64),
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ci_class_time ON class_image(class_id, capture_time);
CREATE INDEX idx_ci_status     ON class_image(vm_analyze_status);

-- 2.6 人脸检测记录
CREATE TABLE face_detect_record (
    id              BIGSERIAL PRIMARY KEY,
    class_image_id  BIGINT NOT NULL REFERENCES class_image(id),
    student_id      BIGINT REFERENCES student(id),
    vm_face_token   VARCHAR(64),
    bbox            JSONB,
    confidence      REAL,
    status          VARCHAR(20) NOT NULL DEFAULT 'detected',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_fdr_image   ON face_detect_record(class_image_id);
CREATE INDEX idx_fdr_student ON face_detect_record(student_id);

-- 2.7 表情识别记录
CREATE TABLE emotion_record (
    id                  BIGSERIAL PRIMARY KEY,
    face_detect_id      BIGINT NOT NULL UNIQUE REFERENCES face_detect_record(id),
    emotion_label       VARCHAR(20) NOT NULL,
    emotion_probability REAL NOT NULL,
    age                 INT,
    gender              VARCHAR(10),
    liveness_score      REAL,
    quality_score       REAL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_er_label ON emotion_record(emotion_label);

-- 2.8 情绪聚合数据
CREATE TABLE emotion_aggregation (
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
CREATE UNIQUE INDEX idx_ea_unique ON emotion_aggregation(student_id, date, period_id);


-- ============================================================
-- Part 3: 预警与干预
-- ============================================================

-- 3.1 预警规则
CREATE TABLE alert_rule (
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

-- 3.2 预警记录
CREATE TABLE alert_log (
    id              BIGSERIAL PRIMARY KEY,
    alert_rule_id   BIGINT NOT NULL REFERENCES alert_rule(id),
    student_id      BIGINT REFERENCES student(id),
    class_id        BIGINT REFERENCES class(id),
    trigger_value   REAL,
    message         TEXT,
    acknowledged    BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_al_student ON alert_log(student_id);
CREATE INDEX idx_al_ack    ON alert_log(acknowledged);

-- 3.3 干预记录
CREATE TABLE intervention_log (
    id              BIGSERIAL PRIMARY KEY,
    student_id      BIGINT NOT NULL REFERENCES student(id),
    teacher_name    VARCHAR(50),
    action_type     VARCHAR(50) NOT NULL,
    description     TEXT,
    effect          VARCHAR(500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_il_student ON intervention_log(student_id);


-- ============================================================
-- Part 4: 初始数据
-- ============================================================

-- 4.1 默认角色
INSERT INTO sys_role (code, name, description, sort_order) VALUES
    ('admin',         '系统管理员', '平台全部管理权限', 1),
    ('school_admin',  '校级管理者', '全校报表查看、重点关注管理', 2),
    ('grade_leader',  '年级组长',   '本年级各班查看和对比', 3),
    ('class_teacher', '班主任',     '本班学生详情、课堂情绪看板', 4),
    ('counselor',     '心理老师',   '重点关注学生、干预记录', 5);

-- 4.2 默认课时段
INSERT INTO class_period (name, period_key, start_time, end_time, sort_order) VALUES
    ('早读-到校',   'arrival',     '06:00', '07:40', 1),
    ('第1节',      'period_1',    '07:40', '08:20', 2),
    ('第2节',      'period_2',    '08:20', '09:00', 3),
    ('第3节',      'period_3',    '09:00', '09:50', 4),
    ('课间操',      'recess',      '09:50', '10:10', 5),
    ('第4节',      'period_4',    '10:10', '10:50', 6),
    ('第5节',      'period_5',    '10:50', '11:40', 7),
    ('午餐-午休',   'lunch',       '11:40', '14:00', 8),
    ('第6节',      'period_6',    '14:00', '14:40', 9),
    ('第7节',      'period_7',    '14:40', '15:30', 10),
    ('第8节',      'period_8',    '15:30', '16:20', 11),
    ('课外活动-放学', 'afterclass', '16:20', '19:00', 12);

-- 4.3 默认字典类型
INSERT INTO sys_dict_type (dict_code, dict_name) VALUES
    ('emotion_label',  '表情标签'),
    ('student_status', '学生状态'),
    ('action_type',    '干预类型'),
    ('period_key',     '课时段标识');

-- 4.4 表情标签字典
INSERT INTO sys_dict_data (dict_type_id, item_code, item_value, sort_order)
SELECT id, 'happy',    '开心',    1 FROM sys_dict_type WHERE dict_code = 'emotion_label'
UNION ALL SELECT id, 'sad',     '悲伤',   2 FROM sys_dict_type WHERE dict_code = 'emotion_label'
UNION ALL SELECT id, 'angry',   '愤怒',   3 FROM sys_dict_type WHERE dict_code = 'emotion_label'
UNION ALL SELECT id, 'surprise','惊讶',   4 FROM sys_dict_type WHERE dict_code = 'emotion_label'
UNION ALL SELECT id, 'fear',    '恐惧',   5 FROM sys_dict_type WHERE dict_code = 'emotion_label'
UNION ALL SELECT id, 'disgust', '厌恶',   6 FROM sys_dict_type WHERE dict_code = 'emotion_label'
UNION ALL SELECT id, 'neutral', '中性',   7 FROM sys_dict_type WHERE dict_code = 'emotion_label';

-- 4.5 默认系统配置
INSERT INTO sys_config (config_key, config_value, description) VALUES
    ('visionmind.api.base_url',    'http://localhost:8080/v1', 'VisionMind 外部API基础地址'),
    ('visionmind.api.face.detect', '/face/detect',             '人脸检测API路径'),
    ('visionmind.api.face.attribute','/face/attribute',        '人脸属性分析API路径'),
    ('visionmind.api.face.search', '/face/search',             '人脸搜索API路径'),
    ('visionmind.api.facedb.register','/facedb/register',     '人脸注册API路径'),
    ('visionmind.detect.confidence', '0.5',                    '人脸检测置信度阈值'),
    ('visionmind.search.threshold',  '0.5',                    '人脸搜索匹配阈值'),
    ('visionmind.search.top_k',      '5',                      '人脸搜索返回topK'),
    ('alert.default.negative_ratio', '0.6',                    '默认负面情绪预警阈值');
