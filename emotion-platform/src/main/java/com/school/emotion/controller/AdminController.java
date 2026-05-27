package com.school.emotion.controller;

import com.school.emotion.service.ImageImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ImageImportService importService;

    public AdminController(ImageImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/import")
    public ResponseEntity<?> importData(@RequestParam String dateDir) {
        var report = importService.importDateDir(Path.of(dateDir));
        return ResponseEntity.ok(Map.of(
                "code", report.error() != null ? 1 : 0,
                "message", report.error() != null ? report.error() : "import completed",
                "data", report));
    }
}
