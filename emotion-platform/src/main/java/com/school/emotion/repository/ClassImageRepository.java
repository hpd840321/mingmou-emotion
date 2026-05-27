package com.school.emotion.repository;

import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;

public interface ClassImageRepository extends JpaRepository<ClassImage, Long> {
    List<ClassImage> findByStatus(ImageStatus status);
    List<ClassImage> findByClassIdAndCaptureTimeBetween(Long classId, OffsetDateTime start, OffsetDateTime end);
    long countByStatus(ImageStatus status);
}
