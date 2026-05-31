ALTER TABLE class_image
  ADD COLUMN face_detected_count INT DEFAULT 0 NOT NULL COMMENT '该图片检测到的人脸数',
  ADD COLUMN emotion_recognized_count INT DEFAULT 0 NOT NULL COMMENT '该图片成功识别的情绪数',
  ADD COLUMN annotated_image_url VARCHAR(1000) DEFAULT NULL COMMENT '标注图路径（含人脸框）';

ALTER TABLE face_record
  ADD COLUMN error_message VARCHAR(500) DEFAULT NULL COMMENT '处理失败原因，成功处理时为空';
