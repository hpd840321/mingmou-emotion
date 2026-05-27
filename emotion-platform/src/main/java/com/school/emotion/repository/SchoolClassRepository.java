package com.school.emotion.repository;

import com.school.emotion.model.entity.SchoolClass;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SchoolClassRepository extends JpaRepository<SchoolClass, Long> {
    List<SchoolClass> findByGrade_Id(Long gradeId);
}
