package com.school.emotion.controller;

import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.repository.EmotionAggregationRepository;
import com.school.emotion.repository.FaceRecordRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/classes")
public class ClassController {

    private final EmotionAggregationRepository aggregationRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final ClassImageRepository classImageRepository;

    public ClassController(EmotionAggregationRepository aggregationRepository,
                           FaceRecordRepository faceRecordRepository,
                           ClassImageRepository classImageRepository) {
        this.aggregationRepository = aggregationRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.classImageRepository = classImageRepository;
    }

    @GetMapping("/{id}/dashboard")
    public ResponseEntity<?> dashboard(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String periodLabel) {
        LocalDate queryDate = (date != null && !date.isEmpty()) ? LocalDate.parse(date) : LocalDate.now();
        var aggs = aggregationRepository.findByClassIdAndDate(id, queryDate);
        Map<String, Object> data = new HashMap<>();
        data.put("classId", id);
        data.put("date", queryDate.toString());
        data.put("periodLabel", periodLabel);
        data.put("aggregations", aggs);
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
        var images = classImageRepository.findAll();
        return ResponseEntity.ok(Map.of("code", 0, "message", "success",
                "data", Map.of("classId", id, "totalImages", images.size())));
    }
}
