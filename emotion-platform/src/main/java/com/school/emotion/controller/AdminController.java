package com.school.emotion.controller;

import com.school.emotion.service.DataDirectoryScanner;
import com.school.emotion.service.FaceProcessingPipeline;
import com.school.emotion.service.ImageImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final ImageImportService importService;
    private final DataDirectoryScanner scanner;
    private final FaceProcessingPipeline pipeline;

    public AdminController(ImageImportService importService,
                           DataDirectoryScanner scanner,
                           FaceProcessingPipeline pipeline) {
        this.importService = importService;
        this.scanner = scanner;
        this.pipeline = pipeline;
    }

    @PostMapping("/import")
    public ResponseEntity<?> importData(@RequestParam String dateDir) {
        var report = importService.importDateDir(Path.of(dateDir));
        return ResponseEntity.ok(Map.of(
                "code", report.error() != null ? 1 : 0,
                "message", report.error() != null ? report.error() : "import completed",
                "data", report));
    }

    @Async("pipelineExecutor")
    @PostMapping("/scan")
    public void scanAll() {
        var report = scanner.scanAll();
        log.info("Scan complete: {} total images, {} imported", report.total(), report.imported());
    }

    // Pipeline /run is now handled by PipelineStatusController (/api/v1/admin/pipeline/run)
}
