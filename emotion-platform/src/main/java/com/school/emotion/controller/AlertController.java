package com.school.emotion.controller;

import com.school.emotion.model.entity.AlertRule;
import com.school.emotion.repository.AlertLogRepository;
import com.school.emotion.repository.AlertRuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/alert-rules")
public class AlertController {

    private final AlertRuleRepository ruleRepository;
    private final AlertLogRepository alertLogRepository;

    public AlertController(AlertRuleRepository ruleRepository, AlertLogRepository alertLogRepository) {
        this.ruleRepository = ruleRepository;
        this.alertLogRepository = alertLogRepository;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody AlertRule rule) {
        rule.setEnabled(true);
        var saved = ruleRepository.save(rule);
        return ResponseEntity.ok(Map.of("code", 0, "message", "created", "data", saved));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", ruleRepository.findAll()));
    }

    @GetMapping("/logs")
    public ResponseEntity<?> logs(@RequestParam(required = false) Long classId) {
        if (classId != null) {
            return ResponseEntity.ok(Map.of("code", 0, "data", alertLogRepository.findByClassIdOrderByCreatedAtDesc(classId)));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", alertLogRepository.findAll()));
    }
}
