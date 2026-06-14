package com.school.emotion.service;

import com.school.emotion.model.entity.AcademicYear;
import com.school.emotion.repository.AcademicYearRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AcademicYearService {

    private final AcademicYearRepository academicYearRepository;

    @Autowired(required = false)
    public AcademicYearService(AcademicYearRepository academicYearRepository) {
        this.academicYearRepository = academicYearRepository;
    }

    public AcademicYear getCurrentAcademicYear() {
        return academicYearRepository.findByIsCurrentTrue()
                .orElseThrow(() -> new RuntimeException("No current academic year found"));
    }

    @Transactional
    public AcademicYear switchAcademicYear(Long newYearId) {
        AcademicYear old = academicYearRepository.findByIsCurrentTrue().orElse(null);
        if (old != null) {
            old.setIsCurrent(false);
            academicYearRepository.save(old);
        }
        AcademicYear newYear = academicYearRepository.findById(newYearId)
                .orElseThrow(() -> new RuntimeException("Academic year not found: " + newYearId));
        newYear.setIsCurrent(true);
        return academicYearRepository.save(newYear);
    }

    public List<AcademicYear> listAll() {
        return academicYearRepository.findAll();
    }
}
