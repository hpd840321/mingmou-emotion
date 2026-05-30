package com.school.emotion.controller;

import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import com.school.emotion.service.FaceProcessingPipeline;
import com.school.emotion.service.ImageImportService;
import com.school.emotion.service.PipelineProgressService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
    private final org.springframework.core.task.TaskExecutor pipelineExecutor;
    @Value("${app.data.dir:./data}")
    private String dataDir;

    public PipelineStatusController(
            ClassImageRepository classImageRepository,
            FaceProcessingPipeline pipeline,
            PipelineProgressService progressService,
            ImageImportService importService,
            org.springframework.core.task.TaskExecutor pipelineExecutor) {
        this.classImageRepository = classImageRepository;
        this.pipeline = pipeline;
        this.progressService = progressService;
        this.importService = importService;
        this.pipelineExecutor = pipelineExecutor;
    }

    @GetMapping("/data-dirs")
    public ResponseEntity<Map<String, Object>> getDataDirs() {
        Path dataRoot = Path.of(dataDir);
        java.util.List<Map<String, Object>> schools = new java.util.ArrayList<>();

        // Build status map from lean query (url + status only, not full entities)
        java.util.Map<String, Map<String, Long>> statusMap = new java.util.HashMap<>();
        for (Object[] row : classImageRepository.findImageUrlAndStatus()) {
            String url = (String) row[0];
            ImageStatus status = (ImageStatus) row[1];
            if (url == null) continue;
            Path parent = Path.of(url).getParent();
            if (parent == null) continue;
            String parentKey = parent.toAbsolutePath().normalize().toString();
            statusMap.computeIfAbsent(parentKey, k -> {
                java.util.Map<String, Long> m = new java.util.LinkedHashMap<>();
                m.put("PENDING", 0L); m.put("PROCESSING", 0L); m.put("COMPLETED", 0L); m.put("FAILED", 0L);
                return m;
            });
            statusMap.get(parentKey).merge(status.name(), 1L, Long::sum);
        }

        try (var schoolStream = java.nio.file.Files.list(dataRoot)) {
            schoolStream.filter(java.nio.file.Files::isDirectory).forEach(schoolDir -> {
                schools.add(buildSchoolNode(schoolDir, statusMap));
            });
        } catch (Exception e) {
            log.warn("Failed to scan data dir '{}': {}", dataRoot.toAbsolutePath(), e.getMessage());
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("schools", schools)));
    }

    private void addStatus(Map<String, Object> node, Path dir, java.util.Map<String, Map<String, Long>> statusMap) {
        String key = dir.toAbsolutePath().normalize().toString();
        java.util.Map<String, Long> st = statusMap.get(key);
        if (st != null) {
            node.putAll(st);
        } else {
            node.put("PENDING", 0L); node.put("PROCESSING", 0L); node.put("COMPLETED", 0L); node.put("FAILED", 0L);
        }
    }

    private Map<String, Object> buildSchoolNode(Path schoolDir, java.util.Map<String, Map<String, Long>> statusMap) {
        Map<String, Object> school = new java.util.LinkedHashMap<>();
        school.put("name", schoolDir.getFileName().toString());
        java.util.List<Map<String, Object>> classes = new java.util.ArrayList<>();
        long totalPending = 0, totalProcessing = 0, totalCompleted = 0, totalFailed = 0;
        try (var stream = java.nio.file.Files.list(schoolDir)) {
            for (var c : stream.filter(java.nio.file.Files::isDirectory).toList()) {
                var cn = buildClassNode(c, statusMap);
                classes.add(cn);
                totalPending += (long)cn.getOrDefault("PENDING", 0L);
                totalProcessing += (long)cn.getOrDefault("PROCESSING", 0L);
                totalCompleted += (long)cn.getOrDefault("COMPLETED", 0L);
                totalFailed += (long)cn.getOrDefault("FAILED", 0L);
            }
        } catch (Exception e) {
            log.warn("Error listing classes in {}: {} {}", schoolDir, e.getClass().getSimpleName(), e.getMessage());
        }
        school.put("classes", classes);
        school.put("count", classes.stream().mapToInt(c -> ((Number)c.get("count")).intValue()).sum());
        school.put("PENDING", totalPending); school.put("PROCESSING", totalProcessing);
        school.put("COMPLETED", totalCompleted); school.put("FAILED", totalFailed);
        return school;
    }

    private Map<String, Object> buildClassNode(Path classDir, java.util.Map<String, Map<String, Long>> statusMap) {
        Map<String, Object> cls = new java.util.LinkedHashMap<>();
        cls.put("name", classDir.getFileName().toString());
        java.util.List<Map<String, Object>> dates = new java.util.ArrayList<>();
        long tp = 0, tpr = 0, tc = 0, tf = 0;
        try (var stream = java.nio.file.Files.list(classDir)) {
            for (var d : stream.filter(java.nio.file.Files::isDirectory).toList()) {
                var dn = buildDateNode(d, statusMap);
                dates.add(dn);
                tp += (long)dn.getOrDefault("PENDING", 0L);
                tpr += (long)dn.getOrDefault("PROCESSING", 0L);
                tc += (long)dn.getOrDefault("COMPLETED", 0L);
                tf += (long)dn.getOrDefault("FAILED", 0L);
            }
        } catch (Exception e) {
            log.warn("Error listing dates in {}: {} {}", classDir, e.getClass().getSimpleName(), e.getMessage());
        }
        cls.put("dates", dates);
        cls.put("count", dates.stream().mapToInt(d -> ((Number)d.get("count")).intValue()).sum());
        cls.put("PENDING", tp); cls.put("PROCESSING", tpr); cls.put("COMPLETED", tc); cls.put("FAILED", tf);
        return cls;
    }

    private Map<String, Object> buildDateNode(Path dateDir, java.util.Map<String, Map<String, Long>> statusMap) {
        Map<String, Object> dateObj = new java.util.LinkedHashMap<>();
        dateObj.put("name", dateDir.getFileName().toString());
        java.util.List<Map<String, Object>> periods = new java.util.ArrayList<>();
        long tp = 0, tpr = 0, tc = 0, tf = 0;
        try (var stream = java.nio.file.Files.list(dateDir)) {
            for (var p : stream.filter(java.nio.file.Files::isDirectory).toList()) {
                var pn = buildPeriodNode(p, statusMap);
                periods.add(pn);
                tp += (long)pn.getOrDefault("PENDING", 0L);
                tpr += (long)pn.getOrDefault("PROCESSING", 0L);
                tc += (long)pn.getOrDefault("COMPLETED", 0L);
                tf += (long)pn.getOrDefault("FAILED", 0L);
            }
        } catch (Exception e) {
            log.warn("Error listing periods in {}: {} {}", dateDir, e.getClass().getSimpleName(), e.getMessage());
        }
        dateObj.put("periods", periods);
        dateObj.put("count", periods.stream().mapToInt(p -> ((Number)p.get("count")).intValue()).sum());
        dateObj.put("PENDING", tp); dateObj.put("PROCESSING", tpr); dateObj.put("COMPLETED", tc); dateObj.put("FAILED", tf);
        return dateObj;
    }

    private Map<String, Object> buildPeriodNode(Path periodDir, java.util.Map<String, Map<String, Long>> statusMap) {
        Map<String, Object> period = new java.util.LinkedHashMap<>();
        period.put("name", periodDir.getFileName().toString());
        try (var stream = java.nio.file.Files.list(periodDir)) {
            long count = stream.filter(p -> p.toString().endsWith(".jpg")).count();
            period.put("count", count);
        } catch (Exception e) {
            log.warn("Error counting images in {}: {} {}", periodDir, e.getClass().getSimpleName(), e.getMessage());
            period.put("count", 0);
        }
        addStatus(period, periodDir, statusMap);
        return period;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        long pending = classImageRepository.countByStatus(ImageStatus.PENDING);
        long processing = classImageRepository.countByStatus(ImageStatus.PROCESSING);
        long completed = classImageRepository.countByStatus(ImageStatus.COMPLETED);
        long failed = classImageRepository.countByStatus(ImageStatus.FAILED);

        // Count total .jpg files in data directory
        long totalFiles = 0;
        Path dataRoot = Path.of(dataDir);
        try (var files = java.nio.file.Files.walk(dataRoot, 10)) {
            totalFiles = files.filter(p -> p.toString().endsWith(".jpg")).count();
        } catch (Exception e) {
            log.warn("Failed to count files in data dir: {}", e.getMessage());
        }

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pending", pending);
        data.put("processing", processing);
        data.put("completed", completed);
        data.put("failed", failed);
        data.put("total", pending + processing + completed + failed);
        data.put("totalFiles", totalFiles);
        data.put("pendingReal", totalFiles - completed - failed); // real pending = all files not yet completed
        data.putAll(progressService.getStatus());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", data));
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runPipeline() {
        progressService.resetCounters();
        progressService.markRunning();
        pipelineExecutor.execute(() -> {
            try {
                var report = pipeline.processAll();
                log.info("Pipeline finished: total={}, detected={}, noFace={}, errors={}, time={}s",
                        report.total(), report.detected(), report.noFace(), report.errors(), report.elapsedSeconds());
            } finally {
                progressService.markStopped();
            }
        });
        return ResponseEntity.ok(Map.of("code", 0, "message", "管线已启动"));
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
