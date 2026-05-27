package com.school.emotion.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/classes")
public class ClassController {

    @GetMapping("/{id}/dashboard")
    public ResponseEntity<?> dashboard(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String periodLabel) {
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", Map.of(
                "classId", id, "date", date, "periodLabel", periodLabel)));
    }

    @GetMapping("/{id}/emotion-trend")
    public ResponseEntity<?> trend(
            @PathVariable Long id,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
    }

    @GetMapping("/{id}/heatmap")
    public ResponseEntity<?> heatmap(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String periodLabel) {
        return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
    }
}
