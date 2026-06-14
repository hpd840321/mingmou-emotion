package com.school.emotion.service;

import com.school.emotion.model.entity.*;
import com.school.emotion.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.IsoFields;
import java.util.List;

@Service
public class ScheduleService {

    private final CourseScheduleRepository courseScheduleRepository;
    private final ClassSessionRepository classSessionRepository;
    private final AcademicYearRepository academicYearRepository;
    private final SchoolClassRepository schoolClassRepository;

    public ScheduleService(CourseScheduleRepository courseScheduleRepository,
                           ClassSessionRepository classSessionRepository,
                           AcademicYearRepository academicYearRepository,
                           SchoolClassRepository schoolClassRepository) {
        this.courseScheduleRepository = courseScheduleRepository;
        this.classSessionRepository = classSessionRepository;
        this.academicYearRepository = academicYearRepository;
        this.schoolClassRepository = schoolClassRepository;
    }

    @Transactional(readOnly = true)
    public List<CourseSchedule> getWeeklySchedule(Long classId, String weekStr) {
        int year = Integer.parseInt(weekStr.substring(0, weekStr.indexOf('-', 2)));
        int week = Integer.parseInt(weekStr.substring(weekStr.indexOf('W') + 1));
        LocalDate firstDayOfWeek = LocalDate.now()
                .with(IsoFields.WEEK_BASED_YEAR, year)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(DayOfWeek.MONDAY);
        return courseScheduleRepository.findByClazz_IdAndDayOfWeek(classId, firstDayOfWeek.getDayOfWeek().getValue());
    }

    @Transactional
    public CourseSchedule createSchedule(Long classId, CourseSchedule courseSchedule) {
        SchoolClass clazz = schoolClassRepository.findById(classId)
                .orElseThrow(() -> new RuntimeException("Class not found: " + classId));
        courseSchedule.setClazz(clazz);
        return courseScheduleRepository.save(courseSchedule);
    }

    @Transactional
    public CourseSchedule updateSchedule(Long classId, Long id, CourseSchedule courseSchedule) {
        CourseSchedule existing = courseScheduleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Schedule not found: " + id));
        existing.setAcademicYear(courseSchedule.getAcademicYear());
        SchoolClass clazz = schoolClassRepository.findById(classId)
                .orElseThrow(() -> new RuntimeException("Class not found: " + classId));
        existing.setClazz(clazz);
        existing.setSubject(courseSchedule.getSubject());
        existing.setTeacherId(courseSchedule.getTeacherId());
        existing.setDayOfWeek(courseSchedule.getDayOfWeek());
        existing.setPeriod(courseSchedule.getPeriod());
        existing.setSemesterId(courseSchedule.getSemesterId());
        return courseScheduleRepository.save(existing);
    }

    @Transactional
    public List<CourseSchedule> batchSetWeeklySchedule(Long classId, String weekStr, List<CourseSchedule> schedules) {
        SchoolClass clazz = schoolClassRepository.findById(classId)
                .orElseThrow(() -> new RuntimeException("Class not found: " + classId));
        courseScheduleRepository.deleteByClazz_Id(classId);
        for (CourseSchedule s : schedules) {
            s.setClazz(clazz);
        }
        return courseScheduleRepository.saveAll(schedules);
    }

    @Transactional
    public List<ClassSession> autoCreateSessions(Long classId, LocalDate date) {
        int dayOfWeek = date.getDayOfWeek().getValue();
        List<CourseSchedule> schedules = courseScheduleRepository.findByClazz_IdAndDayOfWeek(classId, dayOfWeek);
        List<ClassSession> sessions = schedules.stream().map(s -> {
            ClassSession session = new ClassSession();
            session.setAcademicYear(s.getAcademicYear());
            SchoolClass clazz = schoolClassRepository.findById(classId)
                    .orElseThrow(() -> new RuntimeException("Class not found: " + classId));
            session.setClazz(clazz);
            session.setSubject(s.getSubject());
            session.setTeacherId(s.getTeacherId());
            session.setScheduledStart(date.atTime(8, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime());
            session.setScheduledEnd(date.atTime(9, 0).atZone(ZoneId.systemDefault()).toOffsetDateTime());
            session.setStatus("SCHEDULED");
            session.setAnalysisEnabled(true);
            return session;
        }).toList();
        return classSessionRepository.saveAll(sessions);
    }
}
