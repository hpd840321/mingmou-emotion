ALTER TABLE face_record
  ADD COLUMN IF NOT EXISTS cropped_image_url TEXT DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS quality REAL DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS is_registered_to_lib BOOLEAN DEFAULT FALSE NOT NULL,
  ADD COLUMN IF NOT EXISTS registered_at TIMESTAMPTZ DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS lib_face_id VARCHAR(64) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS lib_register_status VARCHAR(20) DEFAULT 'pending' NOT NULL;

-- emotion_aggregation uses student_id=0 as sentinel for class-level aggregates
ALTER TABLE emotion_aggregation DROP CONSTRAINT IF EXISTS emotion_aggregation_student_id_fkey;
