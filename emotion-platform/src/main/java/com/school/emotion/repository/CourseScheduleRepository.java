package com.school.emotion.repository;

import com.school.emotion.model.entity.CourseSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CourseScheduleRepository extends JpaRepository<CourseSchedule, Long> {
    List<CourseSchedule> findByClazz_IdAndDayOfWeek(Long classId, int dayOfWeek);
    List<CourseSchedule> findByClazz_IdAndAcademicYear_Id(Long classId, Long academicYearId);
    void deleteByClazz_Id(Long classId);
}
