package com.school.emotion.controller;

import com.school.emotion.model.entity.Student;
import com.school.emotion.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private final EmotionAggregationRepository aggregationRepository;
    private final StudentRepository studentRepository;
    private final AlertLogRepository alertLogRepository;
    private final InterventionLogRepository interventionLogRepository;

    public StudentController(EmotionAggregationRepository aggregationRepository,
                             StudentRepository studentRepository,
                             AlertLogRepository alertLogRepository,
                             InterventionLogRepository interventionLogRepository) {
        this.aggregationRepository = aggregationRepository;
        this.studentRepository = studentRepository;
        this.alertLogRepository = alertLogRepository;
        this.interventionLogRepository = interventionLogRepository;
    }

    @GetMapping("/{id}/emotion-timeline")
    @Transactional(readOnly = true)
    public ResponseEntity<?> timeline(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String period) {
        LocalDate queryDate = date != null ? LocalDate.parse(date) : LocalDate.now();
        LocalDate startDate = queryDate.minusDays(7);

        Optional<Student> studentOpt = studentRepository.findById(id);
        if (studentOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("code", 404, "message", "student not found"));
        }
        Student student = studentOpt.get();

        var aggs = aggregationRepository.findByStudentIdAndDateBetween(id, startDate, queryDate);
        var alerts = alertLogRepository.findByStudentIdOrderByCreatedAtDesc(id);
        var interventions = interventionLogRepository.findByStudentIdOrderByCreatedAtDesc(id);

        // Build week distribution from aggregations
        Map<String, Float> weekDist = new HashMap<>();
        weekDist.put("happy", 0f); weekDist.put("sad", 0f); weekDist.put("angry", 0f);
        weekDist.put("surprise", 0f); weekDist.put("fear", 0f); weekDist.put("disgust", 0f);
        weekDist.put("neutral", 0f);
        if (!aggs.isEmpty()) {
            for (var agg : aggs) {
                mergeRatio(weekDist, "happy", agg.getRatioHappy());
                mergeRatio(weekDist, "sad", agg.getRatioSad());
                mergeRatio(weekDist, "angry", agg.getRatioAngry());
                mergeRatio(weekDist, "surprise", agg.getRatioSurprise());
                mergeRatio(weekDist, "fear", agg.getRatioFear());
                mergeRatio(weekDist, "disgust", agg.getRatioDisgust());
                mergeRatio(weekDist, "neutral", agg.getRatioNeutral());
            }
            int count = aggs.size();
            if (count > 0) {
                weekDist.replaceAll((k, v) -> v / count);
            }
        }

        double avgEngagement = aggs.stream().mapToDouble(a ->
                a.getEngagementScore() != null ? a.getEngagementScore() : 0).average().orElse(0);
        double healthScore = (1 - weekDist.getOrDefault("sad", 0f)
                - weekDist.getOrDefault("angry", 0f)
                - weekDist.getOrDefault("fear", 0f)
                - weekDist.getOrDefault("disgust", 0f)) * 100;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("studentId", id);
        data.put("studentName", student.getName());
        data.put("studentNo", student.getStudentNo());
        data.put("className", student.getClazz() != null ? student.getClazz().getName() : "");
        data.put("tags", List.of());
        data.put("kpis", List.of(
                Map.of("label", "情绪健康度", "value", Math.round(Math.max(0, healthScore)),
                        "unit", "%", "change", 0, "changeDirection", "flat",
                        "status", healthScore > 60 ? "good" : "warning"),
                Map.of("label", "课堂参与度", "value", Math.round(avgEngagement),
                        "unit", "%", "change", 0, "changeDirection", "flat",
                        "status", avgEngagement > 60 ? "good" : "warning")
        ));
        data.put("trendData", aggs.stream().map(agg -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", agg.getDate() != null ? agg.getDate().toString() : "");
            point.put("happy", agg.getRatioHappy() != null ? agg.getRatioHappy() : 0);
            point.put("sad", agg.getRatioSad() != null ? agg.getRatioSad() : 0);
            point.put("angry", agg.getRatioAngry() != null ? agg.getRatioAngry() : 0);
            point.put("surprise", agg.getRatioSurprise() != null ? agg.getRatioSurprise() : 0);
            point.put("fear", agg.getRatioFear() != null ? agg.getRatioFear() : 0);
            point.put("disgust", agg.getRatioDisgust() != null ? agg.getRatioDisgust() : 0);
            point.put("neutral", agg.getRatioNeutral() != null ? agg.getRatioNeutral() : 0);
            return point;
        }).toList());
        data.put("weekDistribution", weekDist);
        data.put("periodComparison", List.of());
        data.put("alertTimeline", alerts.stream().map(a -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate().toString() : "");
            item.put("period", "");
            item.put("desc", a.getMessage());
            item.put("triggerValue", 0);
            return item;
        }).toList());
        data.put("interventions", interventions);

        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    private void mergeRatio(Map<String, Float> dist, String key, Float value) {
        if (value != null) {
            dist.merge(key, value, Float::sum);
        }
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
