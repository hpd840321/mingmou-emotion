package com.school.emotion.controller;

import com.school.emotion.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private final EmotionAggregationRepository aggregationRepository;
    private final AlertLogRepository alertLogRepository;
    private final InterventionLogRepository interventionLogRepository;

    public StudentController(EmotionAggregationRepository aggregationRepository,
                             AlertLogRepository alertLogRepository,
                             InterventionLogRepository interventionLogRepository) {
        this.aggregationRepository = aggregationRepository;
        this.alertLogRepository = alertLogRepository;
        this.interventionLogRepository = interventionLogRepository;
    }

    @GetMapping("/{id}/emotion-timeline")
    public ResponseEntity<?> timeline(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String period) {
        LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        var aggs = aggregationRepository.findByStudentIdAndDateBetween(id, queryDate.minusDays(7), queryDate);
        var alerts = alertLogRepository.findByStudentIdOrderByCreatedAtDesc(id);
        var interventions = interventionLogRepository.findByStudentIdOrderByCreatedAtDesc(id);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", Map.of(
                "studentId", id, "aggregations", aggs, "alerts", alerts, "interventions", interventions)));
    }

    @GetMapping("/{id}/emotion-report")
    public ResponseEntity<?> report(
            @PathVariable Long id,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        LocalDate startDate = start != null ? LocalDate.parse(start) : LocalDate.now().minusDays(30);
        LocalDate endDate = end != null ? LocalDate.parse(end) : LocalDate.now();
        var aggs = aggregationRepository.findByStudentIdAndDateBetween(id, startDate, endDate);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", aggs));
    }

    @GetMapping("/{id}/alerts")
    public ResponseEntity<?> alerts(@PathVariable Long id) {
        var alerts = alertLogRepository.findByStudentIdOrderByCreatedAtDesc(id);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", alerts));
    }
}
