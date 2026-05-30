package com.school.emotion.repository;

import com.school.emotion.model.entity.FaceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface FaceRecordRepository extends JpaRepository<FaceRecord, Long> {
    List<FaceRecord> findByClassImageId(Long classImageId);
    List<FaceRecord> findByStudentId(Long studentId);
    Optional<FaceRecord> findByLibFaceId(String libFaceId);

    @Query("SELECT DISTINCT fr.classImage.id FROM FaceRecord fr WHERE fr.errorMessage IS NOT NULL")
    List<Long> findClassImageIdsWithFailedFaces();
}
