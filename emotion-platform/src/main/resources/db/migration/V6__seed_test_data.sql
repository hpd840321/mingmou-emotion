INSERT INTO grade (id, name, sort_order) VALUES (1, '初一', 1) ON CONFLICT (id) DO NOTHING;
INSERT INTO class (id, grade_id, name, sort_order) VALUES (1, 1, '初一班', 1) ON CONFLICT (id) DO NOTHING;
INSERT INTO student (id, class_id, student_no, name, status) VALUES (1, 1, '2026001', '张三', 'active') ON CONFLICT (id) DO NOTHING;
INSERT INTO student (id, class_id, student_no, name, status) VALUES (2, 1, '2026002', '李四', 'active') ON CONFLICT (id) DO NOTHING;
INSERT INTO student (id, class_id, student_no, name, status) VALUES (3, 1, '2026003', '王五', 'active') ON CONFLICT (id) DO NOTHING;
INSERT INTO student (id, class_id, student_no, name, status) VALUES (4, 1, '2026004', '赵六', 'active') ON CONFLICT (id) DO NOTHING;
