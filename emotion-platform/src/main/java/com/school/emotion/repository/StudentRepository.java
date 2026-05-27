package com.school.emotion.repository;

import com.school.emotion.model.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    Optional<Student> findByStudentNo(String studentNo);
    List<Student> findByClazz_Id(Long classId);
}
