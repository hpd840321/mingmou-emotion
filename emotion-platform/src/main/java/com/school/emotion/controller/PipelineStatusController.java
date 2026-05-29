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
