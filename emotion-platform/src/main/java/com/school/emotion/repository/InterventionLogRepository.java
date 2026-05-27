package com.school.emotion.repository;

import com.school.emotion.model.entity.InterventionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InterventionLogRepository extends JpaRepository<InterventionLog, Long> {
    List<InterventionLog> findByStudentIdOrderByCreatedAtDesc(Long studentId);
}
