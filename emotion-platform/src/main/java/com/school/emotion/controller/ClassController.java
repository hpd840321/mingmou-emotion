package com.school.emotion.controller;

import com.school.emotion.model.entity.Student;
import com.school.emotion.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/classes")
public class ClassController {

    private final EmotionAggregationRepository aggregationRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final ClassImageRepository classImageRepository;
    private final StudentRepository studentRepository;

    public ClassController(EmotionAggregationRepository aggregationRepository,
                           FaceRecordRepository faceRecordRepository,
                           ClassImageRepository classImageRepository,
                           StudentRepository studentRepository) {
        this.aggregationRepository = aggregationRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.classImageRepository = classImageRepository;
        this.studentRepository = studentRepository;
    }

    @GetMapping("/{id}/dashboard")
    public ResponseEntity<?> dashboard(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String periodLabel) {
        LocalDate queryDate = (date != null && !date.isEmpty()) ? LocalDate.parse(date) : LocalDate.now();
        var aggs = aggregationRepository.findByClassIdAndDate(id, queryDate);

        // Build student rows from Student records, enriching with aggregation data
        List<Student> students = studentRepository.findByClazz_Id(id);
        List<Map<String, Object>> studentRows = students.stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("name", s.getName());
            row.put("studentNo", s.getStudentNo());

            var studentAgg = aggs.stream()
                    .filter(a -> a.getStudentId() != null && a.getStudentId().equals(s.getId()))
                    .findFirst();

            if (studentAgg.isPresent()) {
                var agg = studentAgg.get();
                Map<String, Float> ratios = new LinkedHashMap<>();
                ratios.put("happy", agg.getRatioHappy() != null ? agg.getRatioHappy() : 0);
                ratios.put("neutral", agg.getRatioNeutral() != null ? agg.getRatioNeutral() : 0);
                ratios.put("sad", agg.getRatioSad() != null ? agg.getRatioSad() : 0);
                ratios.put("angry", agg.getRatioAngry() != null ? agg.getRatioAngry() : 0);
                ratios.put("surprise", agg.getRatioSurprise() != null ? agg.getRatioSurprise() : 0);
                ratios.put("fear", agg.getRatioFear() != null ? agg.getRatioFear() : 0);
                ratios.put("disgust", agg.getRatioDisgust() != null ? agg.getRatioDisgust() : 0);
                var max = ratios.entrySet().stream().max(Map.Entry.comparingByValue());

                row.put("dominantEmotion", max.isPresent() && max.get().getValue() > 0 ? max.get().getKey() : null);
                row.put("dominantConfidence", max.isPresent() ? max.get().getValue() : null);
                row.put("happy", agg.getRatioHappy() != null ? Math.round(agg.getRatioHappy() * 100) : 0);
                row.put("neutral", agg.getRatioNeutral() != null ? Math.round(agg.getRatioNeutral() * 100) : 0);
                row.put("sad", agg.getRatioSad() != null ? Math.round(agg.getRatioSad() * 100) : 0);
                row.put("angry", agg.getRatioAngry() != null ? Math.round(agg.getRatioAngry() * 100) : 0);
                row.put("engagement", agg.getEngagementScore() != null ? Math.round(agg.getEngagementScore()) : 0);
            } else {
                row.put("dominantEmotion", null);
                row.put("dominantConfidence", null);
                row.put("happy", 0); row.put("neutral", 0); row.put("sad", 0); row.put("angry", 0);
                row.put("engagement", 0);
            }
            row.put("isAlert", false);
            row.put("isAbsent", false);
            return row;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("classId", id);
        data.put("date", queryDate.toString());
        data.put("periodLabel", periodLabel);
        data.put("aggregations", aggs);
        data.put("students", studentRows);
        data.put("totalPages", 1);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @GetMapping("/{id}/emotion-trend")
    public ResponseEntity<?> trend(
            @PathVariable Long id,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        LocalDate startDate = start != null ? LocalDate.parse(start) : LocalDate.now().minusDays(7);
        LocalDate endDate = end != null ? LocalDate.parse(end) : LocalDate.now();
        var aggs = aggregationRepository.findByClassIdAndDateBetween(id, startDate, endDate);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", aggs));
    }

    @GetMapping("/{id}/heatmap")
    public ResponseEntity<?> heatmap(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String periodLabel) {
        LocalDate queryDate = (date != null && !date.isEmpty()) ? LocalDate.parse(date) : LocalDate.now();
        List<Student> students = studentRepository.findByClazz_Id(id);
        var aggs = aggregationRepository.findByClassIdAndDate(id, queryDate);

        List<Map<String, Object>> seats = new ArrayList<>();
        int[] idx = {0};
        students.forEach(s -> {
            Map<String, Object> seat = new LinkedHashMap<>();
            seat.put("row", idx[0] / 8);
            seat.put("col", idx[0] % 8);
            idx[0]++;
            seat.put("studentId", s.getId());
            seat.put("studentName", s.getName());
            seat.put("studentNo", s.getStudentNo());

            var match = aggs.stream().filter(a -> s.getId().equals(a.getStudentId())).findFirst();
            if (match.isPresent()) {
                seat.put("engagement", match.get().getEngagementScore());
                Map<String, Float> ratios = new LinkedHashMap<>();
                ratios.put("happy", match.get().getRatioHappy());
                ratios.put("neutral", match.get().getRatioNeutral());
                ratios.put("sad", match.get().getRatioSad());
                ratios.put("angry", match.get().getRatioAngry());
                ratios.put("surprise", match.get().getRatioSurprise());
                ratios.put("fear", match.get().getRatioFear());
                ratios.put("disgust", match.get().getRatioDisgust());
                var max = ratios.entrySet().stream()
                        .filter(e -> e.getValue() != null && e.getValue() > 0)
                        .max(Map.Entry.comparingByValue());
                seat.put("dominantEmotion", max.isPresent() ? max.get().getKey() : null);
            } else {
                seat.put("engagement", null);
                seat.put("dominantEmotion", null);
            }
            seat.put("isAbsent", false);
            seat.put("isEmpty", false);
            seats.add(seat);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seats", seats);
        data.put("rows", students.isEmpty() ? 0 : (students.size() - 1) / 8 + 1);
        data.put("cols", 8);
        data.put("distribution", List.of());
        data.put("lowEngagementAlerts", List.of());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }
}
