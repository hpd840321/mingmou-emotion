package com.school.emotion.repository;

import com.school.emotion.model.entity.EmotionAggregation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EmotionAggregationRepository extends JpaRepository<EmotionAggregation, Long> {
    Optional<EmotionAggregation> findByStudentIdAndDateAndPeriodId(Long studentId, LocalDate date, Long periodId);
    List<EmotionAggregation> findByStudentIdAndDateBetween(Long studentId, LocalDate start, LocalDate end);
    List<EmotionAggregation> findByClassIdAndDate(Long classId, LocalDate date);
    List<EmotionAggregation> findByClassIdAndDateBetween(Long classId, LocalDate start, LocalDate end);
}
