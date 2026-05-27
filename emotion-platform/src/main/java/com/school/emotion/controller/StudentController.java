package com.school.emotion.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    @GetMapping("/{id}/emotion-timeline")
    public ResponseEntity<?> timeline(
            @PathVariable Long id,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String period) {
        return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
    }

    @GetMapping("/{id}/emotion-report")
    public ResponseEntity<?> report(
            @PathVariable Long id,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
    }

    @GetMapping("/{id}/alerts")
    public ResponseEntity<?> alerts(@PathVariable Long id) {
        return ResponseEntity.ok(Map.of("code", 0, "message", "success"));
    }
}
