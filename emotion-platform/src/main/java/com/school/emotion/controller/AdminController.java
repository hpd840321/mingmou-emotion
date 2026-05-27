package com.school.emotion.controller;

import com.school.emotion.service.DataDirectoryScanner;
import com.school.emotion.service.ImageImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ImageImportService importService;
    private final DataDirectoryScanner scanner;

    public AdminController(ImageImportService importService, DataDirectoryScanner scanner) {
        this.importService = importService;
        this.scanner = scanner;
    }

    @PostMapping("/import")
    public ResponseEntity<?> importData(@RequestParam String dateDir) {
        var report = importService.importDateDir(Path.of(dateDir));
        return ResponseEntity.ok(Map.of(
                "code", report.error() != null ? 1 : 0,
                "message", report.error() != null ? report.error() : "import completed",
                "data", report));
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scanAll() {
        var report = scanner.scanAll();
        return ResponseEntity.ok(Map.of(
                "code", report.error() != null ? 1 : 0,
                "message", report.error() != null ? report.error() : "scan completed",
                "data", Map.of("total", report.total(), "imported", report.imported())));
    }
}
