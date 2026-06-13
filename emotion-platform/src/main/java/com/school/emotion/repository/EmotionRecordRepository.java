package com.school.emotion.repository;

import com.school.emotion.model.entity.EmotionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EmotionRecordRepository extends JpaRepository<EmotionRecord, Long> {
    EmotionRecord findByFaceRecordId(Long faceRecordId);

    /** 只查已关联 student 的情绪记录（可推送的） */
    @Query("SELECT er FROM EmotionRecord er JOIN FETCH er.faceRecord fr JOIN FETCH fr.classImage ci WHERE fr.student IS NOT NULL ORDER BY er.id")
    List<EmotionRecord> findPushedRecords();

    /** 按 student 分页 */
    @Query("SELECT er FROM EmotionRecord er JOIN FETCH er.faceRecord fr JOIN FETCH fr.classImage ci WHERE fr.student.id = :studentId ORDER BY er.id")
    List<EmotionRecord> findByStudentId(Long studentId);
}
