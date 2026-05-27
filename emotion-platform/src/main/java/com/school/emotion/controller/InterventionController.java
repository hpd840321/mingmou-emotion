package com.school.emotion.controller;

import com.school.emotion.model.entity.InterventionLog;
import com.school.emotion.repository.InterventionLogRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/interventions")
public class InterventionController {

    private final InterventionLogRepository repository;

    public InterventionController(InterventionLogRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody InterventionLog log) {
        var saved = repository.save(log);
        return ResponseEntity.ok(Map.of("code", 0, "message", "created", "data", saved));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam Long studentId) {
        return ResponseEntity.ok(Map.of("code", 0, "data",
                repository.findByStudentIdOrderByCreatedAtDesc(studentId)));
    }
}
