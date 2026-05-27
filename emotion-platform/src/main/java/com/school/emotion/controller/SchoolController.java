package com.school.emotion.controller;

import com.school.emotion.model.dto.SchoolOverviewDTO;
import com.school.emotion.repository.AlertLogRepository;
import com.school.emotion.service.DashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/school")
public class SchoolController {

    private final DashboardService dashboardService;
    private final AlertLogRepository alertLogRepository;

    public SchoolController(DashboardService dashboardService, AlertLogRepository alertLogRepository) {
        this.dashboardService = dashboardService;
        this.alertLogRepository = alertLogRepository;
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(
            @RequestParam(required = false) Long gradeId,
            @RequestParam(required = false) String period) {
        SchoolOverviewDTO data = dashboardService.getSchoolOverview(gradeId, period);
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @GetMapping("/alerts")
    public ResponseEntity<?> alerts(@RequestParam(required = false) String status) {
        var alerts = alertLogRepository.findAll();
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", alerts));
    }
}
