package com.school.emotion.controller;

import com.school.emotion.service.ExternalEmotionPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/external-push")
public class ExternalPushController {

    private static final Logger log = LoggerFactory.getLogger(ExternalPushController.class);
    private final ExternalEmotionPushService pushService;

    public ExternalPushController(ExternalEmotionPushService pushService) {
        this.pushService = pushService;
    }

    @PostMapping
    public ResponseEntity<?> push(@RequestParam(defaultValue = "all") String type) {
        log.info("Manual external push triggered: type={}", type);
        try {
            switch (type) {
                case "students" -> pushService.pushAllStudents();
                case "emotions" -> pushService.pushAllEmotions();
                default -> pushService.pushAll();
            }
            return ResponseEntity.ok(Map.of("code", 0, "message", "push triggered: " + type));
        } catch (Exception e) {
            log.error("External push failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("code", 1, "message", "push failed: " + e.getMessage()));
        }
    }
}
