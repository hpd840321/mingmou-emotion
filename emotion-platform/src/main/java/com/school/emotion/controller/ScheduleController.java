package com.school.emotion.controller;

import com.school.emotion.model.dto.BatchScheduleRequest;
import com.school.emotion.model.entity.CourseSchedule;
import com.school.emotion.service.ScheduleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/class")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/{classId}/schedule")
    public ResponseEntity<?> getWeeklySchedule(
            @PathVariable Long classId,
            @RequestParam(defaultValue = "2026-W01") String week) {
        List<CourseSchedule> data = scheduleService.getWeeklySchedule(classId, week);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @PostMapping("/{classId}/schedule")
    public ResponseEntity<?> createSchedule(
            @PathVariable Long classId,
            @RequestBody CourseSchedule courseSchedule) {
        CourseSchedule data = scheduleService.createSchedule(classId, courseSchedule);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @PutMapping("/{classId}/schedule/{id}")
    public ResponseEntity<?> updateSchedule(
            @PathVariable Long classId,
            @PathVariable Long id,
            @RequestBody CourseSchedule courseSchedule) {
        CourseSchedule data = scheduleService.updateSchedule(classId, id, courseSchedule);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @PostMapping("/{classId}/schedule/batch")
    public ResponseEntity<?> batchSetWeeklySchedule(
            @PathVariable Long classId,
            @RequestBody BatchScheduleRequest request) {
        List<CourseSchedule> data = scheduleService.batchSetWeeklySchedule(classId, request.getWeek(), request.getEntries());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }
}
