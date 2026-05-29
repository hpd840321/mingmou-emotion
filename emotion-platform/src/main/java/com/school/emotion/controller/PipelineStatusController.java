package com.school.emotion.controller;

import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.service.FaceProcessingPipeline;
import com.school.emotion.service.ImageImportService;
import com.school.emotion.service.PipelineProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/v1/admin/pipeline")
public class PipelineStatusController {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusController.class);

    private final ClassImageRepository classImageRepository;
    private final FaceProcessingPipeline pipeline;
    private final PipelineProgressService progressService;
    private final ImageImportService importService;

    public PipelineStatusController(
            ClassImageRepository classImageRepository,
            FaceProcessingPipeline pipeline,
            PipelineProgressService progressService,
            ImageImportService importService) {
        this.classImageRepository = classImageRepository;
        this.pipeline = pipeline;
        this.progressService = progressService;
        this.importService = importService;
    }

    @GetMapping("/data-dirs")
    public ResponseEntity<Map<String, Object>> getDataDirs(
            @Value("${app.data.dir:./data}") String dataDir) {
        // Scan data/ directory structure (school → class → date → period)
        Path dataRoot = Path.of(dataDir);
        java.util.List<Map<String, Object>> schools = new java.util.ArrayList<>();
        try (var schoolStream = java.nio.file.Files.list(dataRoot)) {
            schoolStream.filter(java.nio.file.Files::isDirectory).forEach(schoolDir -> {
                Map<String, Object> school = new java.util.LinkedHashMap<>();
                school.put("name", schoolDir.getFileName().toString());
                java.util.List<Map<String, Object>> classes = new java.util.ArrayList<>();
                try (var classStream = java.nio.file.Files.list(schoolDir)) {
                    classStream.filter(java.nio.file.Files::isDirectory).forEach(classDir -> {
                        Map<String, Object> cls = new java.util.LinkedHashMap<>();
                        cls.put("name", classDir.getFileName().toString());
                        java.util.List<Map<String, Object>> dates = new java.util.ArrayList<>();
                        try (var dateStream = java.nio.file.Files.list(classDir)) {
                            dateStream.filter(java.nio.file.Files::isDirectory).forEach(dateDir -> {
                                Map<String, Object> dateObj = new java.util.LinkedHashMap<>();
                                dateObj.put("name", dateDir.getFileName().toString());
                                java.util.List<String> periods = new java.util.ArrayList<>();
                                try (var periodStream = java.nio.file.Files.list(dateDir)) {
                                    periodStream.filter(java.nio.file.Files::isDirectory)
                                            .forEach(p -> periods.add(p.getFileName().toString()));
                                } catch (Exception ignored) {}
                                dateObj.put("periods", periods);
                                dates.add(dateObj);
                            });
                        } catch (Exception ignored) {}
                        cls.put("dates", dates);
                        classes.add(cls);
                    });
                } catch (Exception ignored) {}
                school.put("classes", classes);
                schools.add(school);
            });
        } catch (Exception e) {
            log.warn("Failed to scan data dir: {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("schools", schools)));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        long pending = classImageRepository.countByStatus(ImageStatus.PENDING);
        long processing = classImageRepository.countByStatus(ImageStatus.PROCESSING);
        long completed = classImageRepository.countByStatus(ImageStatus.COMPLETED);
        long failed = classImageRepository.countByStatus(ImageStatus.FAILED);

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pending", pending);
        data.put("processing", processing);
        data.put("completed", completed);
        data.put("failed", failed);
        data.put("total", pending + processing + completed + failed);
        data.putAll(progressService.getStatus());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @Async("pipelineExecutor")
    @PostMapping("/run")
    public void runPipeline() {
        progressService.resetCounters();
        progressService.markRunning();
        try {
            var report = pipeline.processAll();
            log.info("Pipeline finished: total={}, detected={}, noFace={}, errors={}, time={}s",
                    report.total(), report.detected(), report.noFace(), report.errors(), report.elapsedSeconds());
        } finally {
            progressService.markStopped();
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopPipeline() {
        progressService.requestStop();
        return ResponseEntity.ok(Map.of("code", 0, "message", "停止信号已发送"));
    }

    @PostMapping("/reset-failed")
    public ResponseEntity<Map<String, Object>> resetFailed() {
        int count = pipeline.resetFailedToPending();
        return ResponseEntity.ok(Map.of("code", 0, "message", "已重置 " + count + " 张图片为待处理", "data", Map.of("resetCount", count)));
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importData(@RequestParam(defaultValue = "./data") String dateDir) {
        var report = importService.importDateDir(Path.of(dateDir));
        return ResponseEntity.ok(Map.of(
                "code", report.error() != null ? 1 : 0,
                "message", report.error() != null ? report.error() : "import completed",
                "data", Map.of("total", report.total(), "imported", report.imported(), "failed", report.failed())
        ));
    }
}
