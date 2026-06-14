package com.school.emotion.controller;

import com.school.emotion.model.entity.ClassSession;
import com.school.emotion.repository.ClassSessionRepository;
import com.school.emotion.service.ClassroomAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/class")
public class ClassroomAnalysisController {

    private final ClassroomAnalysisService analysisService;
    private final ClassSessionRepository classSessionRepository;

    public ClassroomAnalysisController(ClassroomAnalysisService analysisService,
                                        ClassSessionRepository classSessionRepository) {
        this.analysisService = analysisService;
        this.classSessionRepository = classSessionRepository;
    }

    @GetMapping("/{classId}/analysis")
    public ResponseEntity<Map<String, Object>> getAnalysis(
            @PathVariable Long classId,
            @RequestParam(required = false) Long sessionId) {
        var result = analysisService.analyze(classId, sessionId);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", result));
    }

    @PostMapping("/{sessionId}/toggle-analysis")
    public ResponseEntity<?> toggleAnalysis(
            @PathVariable Long sessionId,
            @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        ClassSession session = classSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));
        session.setAnalysisEnabled(enabled);
        classSessionRepository.save(session);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success",
                "data", Map.of("sessionId", sessionId, "analysisEnabled", enabled)));
    }
}
