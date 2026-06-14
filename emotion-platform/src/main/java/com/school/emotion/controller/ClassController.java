package com.school.emotion.controller;

import com.school.emotion.model.entity.Student;
import com.school.emotion.repository.*;
import org.springframework.transaction.annotation.Transactional;
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
    private final EmotionRecordRepository emotionRecordRepository;

    public ClassController(EmotionAggregationRepository aggregationRepository,
                           FaceRecordRepository faceRecordRepository,
                           ClassImageRepository classImageRepository,
                           StudentRepository studentRepository,
                           EmotionRecordRepository emotionRecordRepository) {
        this.aggregationRepository = aggregationRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.classImageRepository = classImageRepository;
        this.studentRepository = studentRepository;
        this.emotionRecordRepository = emotionRecordRepository;
    }

    @GetMapping("/{id}/dashboard")
    @Transactional(readOnly = true)
    public ResponseEntity<?> dashboard(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String periodLabel) {
        LocalDate queryDate = (date != null && !date.isEmpty()) ? LocalDate.parse(date) : LocalDate.now();
        var aggs = aggregationRepository.findByClassIdAndDate(id, queryDate);

        // Build student rows from Student records, enriching with aggregation data
        List<Student> students = studentRepository.findByClazz_Id(id);
        List<Map<String, Object>> studentRows = new ArrayList<>();

        if (!students.isEmpty()) {
            studentRows = students.stream().map(s -> {
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
        }).collect(java.util.stream.Collectors.toList());
        } else {
            // Fallback: derive face-level records from emotion_record + face_record
            Map<String, Long> emotionCounts = emotionRecordRepository.findAll().stream()
                    .filter(er -> er.getFaceRecord() != null)
                    .filter(er -> {
                        var fci = er.getFaceRecord().getClassImage();
                        return fci != null && fci.getClazz() != null && fci.getClazz().getId().equals(id);
                    })
                    .collect(java.util.stream.Collectors.groupingBy(
                            er -> er.getDominantEmotion() != null ? er.getDominantEmotion() : "neutral",
                            java.util.stream.Collectors.counting()));
            long total = emotionCounts.values().stream().mapToLong(Long::longValue).sum();

            // Create virtual student rows from emotion records
            var erList = emotionRecordRepository.findAll().stream()
                    .filter(er -> er.getFaceRecord() != null)
                    .filter(er -> {
                        var fci = er.getFaceRecord().getClassImage();
                        return fci != null && fci.getClazz() != null && fci.getClazz().getId().equals(id);
                    })
                    .limit(200)
                    .toList();

            for (com.school.emotion.model.entity.EmotionRecord er : erList) {
                Map<String, Object> row = new LinkedHashMap<>();
                var fr = er.getFaceRecord();
                var ci = fr.getClassImage();
                row.put("id", "face_" + fr.getId());
                row.put("name", "人脸#" + fr.getId());
                row.put("studentNo", "");
                row.put("faceRecordId", fr.getId());
                row.put("croppedImageUrl", "/img/" + fr.getId());
                row.put("dominantEmotion", er.getDominantEmotion());
                row.put("dominantConfidence", er.getDominantConfidence());
                row.put("happy", er.getEmotionHappy() != null ? Math.round(er.getEmotionHappy() * 100) : 0);
                row.put("neutral", er.getEmotionNeutral() != null ? Math.round(er.getEmotionNeutral() * 100) : 0);
                row.put("sad", er.getEmotionSad() != null ? Math.round(er.getEmotionSad() * 100) : 0);
                row.put("angry", er.getEmotionAngry() != null ? Math.round(er.getEmotionAngry() * 100) : 0);
                row.put("engagement", er.getDominantConfidence() != null ? Math.round(er.getDominantConfidence() * 100) : 0);
                row.put("captureTime", ci != null && ci.getCaptureTime() != null ? ci.getCaptureTime().toString() : null);
                row.put("periodLabel", ci != null ? ci.getPeriodLabel() : null);
                row.put("isAlert", false);
                row.put("isAbsent", false);
                studentRows.add(row);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("classId", id);
        data.put("className", classImageRepository.findById(id)
                .map(ci -> ci.getClazz().getName()).orElse("Class #" + id));
        data.put("date", queryDate.toString());
        data.put("periodLabel", periodLabel);
        data.put("aggregations", aggs);
        data.put("students", studentRows);
        data.put("totalPages", 1);
        
        // KPIs derived from class-level aggregations
        double avgHealth = aggs.stream().mapToDouble(a ->
                a.getPositiveRatio() != null ? a.getPositiveRatio() * 100 : 0).average().orElse(0);
        double avgEngagement = aggs.stream().mapToDouble(a ->
                a.getEngagementScore() != null ? a.getEngagementScore() : 0).average().orElse(0);
        data.put("kpis", List.of(
            createKpiMap("情绪健康度", Math.round(avgHealth), "%", avgHealth > 60 ? "good" : "warning"),
            createKpiMap("课堂参与度", Math.round(avgEngagement), "%", avgEngagement > 60 ? "good" : "warning")
        ));
        
        // Timeline data — group aggregations by date
        List<Map<String, Object>> timeline = aggs.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                a -> a.getDate() != null ? a.getDate().toString() : "",
                java.util.stream.Collectors.averagingDouble(
                    a -> a.getEngagementScore() != null ? a.getEngagementScore() : 0)))
            .entrySet().stream()
            .map(e -> {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("time", e.getKey());
                pt.put("engagement", Math.round(e.getValue()));
                return pt;
            })
            .sorted(java.util.Comparator.comparing(p -> (String) p.get("time")))
            .toList();
        data.put("timelineData", timeline);
        
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    private Map<String, Object> createKpiMap(String label, long value, String unit, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        m.put("unit", unit);
        m.put("change", null);
        m.put("changeDirection", "flat");
        m.put("status", status);
        return m;
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
