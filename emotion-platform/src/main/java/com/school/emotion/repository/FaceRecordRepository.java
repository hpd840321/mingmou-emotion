package com.school.emotion.repository;

import com.school.emotion.model.entity.FaceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FaceRecordRepository extends JpaRepository<FaceRecord, Long> {
    List<FaceRecord> findByClassImageId(Long classImageId);
    List<FaceRecord> findByStudentId(Long studentId);
}
