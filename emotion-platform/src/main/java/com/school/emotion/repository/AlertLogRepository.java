package com.school.emotion.repository;

import com.school.emotion.model.entity.AlertLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AlertLogRepository extends JpaRepository<AlertLog, Long> {
    boolean existsByAlertRuleIdAndStudentIdAndAcknowledgedFalse(Long alertRuleId, Long studentId);
    List<AlertLog> findByStudentIdOrderByCreatedAtDesc(Long studentId);
    List<AlertLog> findByClassIdOrderByCreatedAtDesc(Long classId);
    long countByAcknowledgedFalse();
}
