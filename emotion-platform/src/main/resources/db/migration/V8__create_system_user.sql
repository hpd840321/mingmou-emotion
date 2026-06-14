CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(120) NOT NULL,
    name VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL,
    grade_id BIGINT,
    class_id BIGINT
);

-- Default users (password is "123456" for all)
-- BCrypt hash for "123456": $2a$10$TI9sfqQGSj.bDK0GDdpfGO4NjNTDuvV9P4ddTVDGQQjsLXFbp8baq
INSERT INTO sys_user (username, password, name, role, grade_id, class_id) VALUES
('admin',    '$2a$10$TI9sfqQGSj.bDK0GDdpfGO4NjNTDuvV9P4ddTVDGQQjsLXFbp8baq', '系统管理员', 'admin',          NULL, NULL),
('principal','$2a$10$TI9sfqQGSj.bDK0GDdpfGO4NjNTDuvV9P4ddTVDGQQjsLXFbp8baq', '张校长',     'school_manager', 1,    NULL),
('teacher1', '$2a$10$TI9sfqQGSj.bDK0GDdpfGO4NjNTDuvV9P4ddTVDGQQjsLXFbp8baq', '李老师',     'teacher',         1,    1),
('counselor', '$2a$10$TI9sfqQGSj.bDK0GDdpfGO4NjNTDuvV9P4ddTVDGQQjsLXFbp8baq', '王老师',     'counselor',       1,    NULL),
('student1', '$2a$10$TI9sfqQGSj.bDK0GDdpfGO4NjNTDuvV9P4ddTVDGQQjsLXFbp8baq', '赵同学',     'student',         NULL, 1),
('parent1',  '$2a$10$TI9sfqQGSj.bDK0GDdpfGO4NjNTDuvV9P4ddTVDGQQjsLXFbp8baq', '刘家长',     'parent',          NULL, NULL)
ON CONFLICT (username) DO NOTHING;
