package com.school.emotion.repository;

import com.school.emotion.model.entity.EmotionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmotionRecordRepository extends JpaRepository<EmotionRecord, Long> {
    EmotionRecord findByFaceRecordId(Long faceRecordId);
}
