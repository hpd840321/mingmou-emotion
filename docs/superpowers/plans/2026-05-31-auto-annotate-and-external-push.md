# Auto-Annotate & External Push Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-create Student records from face clusters (with default names), backfill student_id for dashboard data, and push student/emotion data to external system.

**Architecture:** Two-phase approach — Phase 1 extends FaceClusteringServiceV2 to auto-annotate clusters and backfill student_ids (engine-independent). Phase 2 adds an ExternalEmotionPushClient/Service that pushes data to `ylcs.htface.cn:33895/api/Page/Execute` (code ready but disabled until engine restored). FaceClusterPage UI updated to show default names and allow rename.

**Tech Stack:** Java 17, Spring Boot 3.2, JPA/Hibernate, MySQL, Vue 3 + TypeScript, Element Plus

---

### Task 1: FaceCluster entity — add student_id field

**Files:**
- Modify: `emotion-platform/src/main/java/com/school/emotion/model/entity/FaceCluster.java`
- Test: (no test, simple entity change)

- [ ] **Step 1: Add studentId field to FaceCluster**

Add after `lastSeenAt` field:
```java
@Column(name = "student_id")
private Long studentId;
```

Add getter/setter:
```java
public Long getStudentId() { return studentId; }
public void setStudentId(Long studentId) { this.studentId = studentId; }
```

- [ ] **Step 2: Commit**

```bash
git add emotion-platform/src/main/java/com/school/emotion/model/entity/FaceCluster.java
git commit -m "feat: add student_id field to FaceCluster entity"
```

---

### Task 2: FaceClusterVO — add display fields

**Files:**
- Modify: `emotion-platform/src/main/java/com/school/emotion/model/dto/FaceClusterVO.java`

- [ ] **Step 1: Add fields to FaceClusterVO**

```java
private Long studentId;
private String studentName;
private String studentNo;
private Boolean autoAnnotated;

public Long getStudentId() { return studentId; }
public void setStudentId(Long studentId) { this.studentId = studentId; }
public String getStudentName() { return studentName; }
public void setStudentName(String studentName) { this.studentName = studentName; }
public String getStudentNo() { return studentNo; }
public void setStudentNo(String studentNo) { this.studentNo = studentNo; }
public Boolean getAutoAnnotated() { return autoAnnotated; }
public void setAutoAnnotated(Boolean autoAnnotated) { this.autoAnnotated = autoAnnotated; }
```

- [ ] **Step 2: Commit**

```bash
git add emotion-platform/src/main/java/com/school/emotion/model/dto/FaceClusterVO.java
git commit -m "feat: add student display fields to FaceClusterVO"
```

---

### Task 3: FaceRecordRepository — add findByLibFaceId

**Files:**
- Modify: `emotion-platform/src/main/java/com/school/emotion/repository/FaceRecordRepository.java`

- [ ] **Step 1: Add query method**

```java
import com.school.emotion.model.entity.FaceRecord;
import java.util.Optional;

public interface FaceRecordRepository extends JpaRepository<FaceRecord, Long> {
    // ... existing methods ...
    Optional<FaceRecord> findByLibFaceId(String libFaceId);
}
```

- [ ] **Step 2: Commit**

```bash
git add emotion-platform/src/main/java/com/school/emotion/repository/FaceRecordRepository.java
git commit -m "feat: add findByLibFaceId to FaceRecordRepository"
```

---

### Task 4: FaceClusteringServiceV2 — auto-annotate clusters

**Files:**
- Modify: `emotion-platform/src/main/java/com/school/emotion/service/FaceClusteringServiceV2.java`
- Inject: `StudentRepository`, `FaceRecordRepository`, `SchoolClassRepository`, `ExternalEmotionPushService` (optional)

- [ ] **Step 1: Inject new dependencies**

```java
import com.school.emotion.model.entity.Student;
import com.school.emotion.repository.StudentRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.repository.SchoolClassRepository;
// ExternalEmotionPushService will be injected later (Task 7)

public class FaceClusteringServiceV2 {
    private final StudentRepository studentRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final SchoolClassRepository schoolClassRepository;

    public FaceClusteringServiceV2(
            RestTemplate restTemplate,
            FaceClusterRepository clusterRepository,
            StudentRepository studentRepository,
            FaceRecordRepository faceRecordRepository,
            SchoolClassRepository schoolClassRepository,
            @Value("${app.clustering.qdrant-url:http://localhost:6333}") String qdrantUrl,
            @Value("${app.clustering.similarity-threshold:0.7}") float similarityThreshold,
            @Value("${app.clustering.min-cluster-size:3}") int minClusterSize) {
        this.restTemplate = restTemplate;
        this.clusterRepository = clusterRepository;
        this.studentRepository = studentRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.schoolClassRepository = schoolClassRepository;
        this.qdrantUrl = qdrantUrl;
        this.similarityThreshold = similarityThreshold;
        this.minClusterSize = minClusterSize;
    }
    // ... rest stays the same ...
```

- [ ] **Step 2: Add autoAnnotateClusters() method**

Add this method to FaceClusteringServiceV2:

```java
@Transactional
public void autoAnnotateClusters() {
    List<FaceCluster> clusters = clusterRepository.findByStatus("pending");
    if (clusters.isEmpty()) {
        log.info("No pending clusters to auto-annotate");
        return;
    }
    log.info("Auto-annotating {} clusters", clusters.size());

    for (FaceCluster cluster : clusters) {
        try {
            Long classId = cluster.getClassId();
            if (classId == null || classId == 0L) {
                log.warn("Cluster {} has no classId, skipping", cluster.getId());
                continue;
            }

            // Generate sequential student number
            long existingCount = studentRepository.countByStudentNoStartingWith("auto_" + classId + "_");
            int seq = (int) existingCount + 1;
            String studentNo = String.format("auto_%d_%d", classId, cluster.getId());
            String studentName = String.format("student%03d", seq);

            // Create Student
            Student student = new Student();
            student.setStudentNo(studentNo);
            student.setName(studentName);
            student.setClazz(schoolClassRepository.getReferenceById(classId));
            student.setStatus("active");
            student = studentRepository.save(student);
            log.info("Created student {} ({}) for cluster {}", studentNo, studentName, cluster.getId());

            // Backfill face_record.student_id from face_tokens
            String faceTokens = cluster.getFaceTokens();
            if (faceTokens != null && !faceTokens.isEmpty()) {
                // Parse JSON array: ["face_1_2_3", "face_1_2_4", ...]
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(faceTokens);
                int backfilled = 0;
                while (matcher.find()) {
                    String libFaceId = matcher.group(1);
                    var faceRecordOpt = faceRecordRepository.findByLibFaceId(libFaceId);
                    if (faceRecordOpt.isPresent()) {
                        var fr = faceRecordOpt.get();
                        if (fr.getStudent() == null) {
                            fr.setStudent(student);
                            faceRecordRepository.save(fr);
                            backfilled++;
                        }
                    }
                }
                log.info("Backfilled {} face_records for cluster {}", backfilled, cluster.getId());
            }

            // Update cluster
            cluster.setStudentId(student.getId());
            cluster.setStatus("auto_annotated");
            clusterRepository.save(cluster);

        } catch (Exception e) {
            log.error("Failed to auto-annotate cluster {}: {}", cluster.getId(), e.getMessage());
        }
    }
    log.info("Auto-annotation complete");
}
```

- [ ] **Step 3: Add countByStudentNoStartingWith to StudentRepository**

```java
// In StudentRepository.java
long countByStudentNoStartingWith(String prefix);
```

- [ ] **Step 4: Add findByStatus to FaceClusterRepository**

```java
// In FaceClusterRepository.java
import java.util.List;
List<FaceCluster> findByStatus(String status);
```

- [ ] **Step 5: Call autoAnnotateClusters() at end of runClustering()**

In `runClustering()`, after the "Clustering done" log, add:
```java
autoAnnotateClusters();
```

- [ ] **Step 6: Commit**

```bash
git add emotion-platform/src/main/java/com/school/emotion/service/FaceClusteringServiceV2.java
git add emotion-platform/src/main/java/com/school/emotion/repository/FaceRecordRepository.java
git add emotion-platform/src/main/java/com/school/emotion/repository/StudentRepository.java
git add emotion-platform/src/main/java/com/school/emotion/repository/FaceClusterRepository.java
git commit -m "feat: auto-create Student and backfill face_record on clustering"
```

---

### Task 5: FaceLibraryService — add renameCluster + update listPendingClusters

**Files:**
- Modify: `emotion-platform/src/main/java/com/school/emotion/service/FaceLibraryService.java`

- [ ] **Step 1: Add renameCluster() method**

```java
@Transactional
public void renameCluster(Long clusterId, String newName) {
    FaceCluster cluster = clusterRepository.findById(clusterId)
            .orElseThrow(() -> new IllegalArgumentException("Cluster not found: " + clusterId));
    if (cluster.getStudentId() == null) {
        throw new IllegalStateException("Cluster has no associated student: " + clusterId);
    }
    Student student = studentRepository.findById(cluster.getStudentId())
            .orElseThrow(() -> new IllegalArgumentException("Student not found: " + cluster.getStudentId()));
    student.setName(newName);
    studentRepository.save(student);
    cluster.setStatus("renamed");
    clusterRepository.save(cluster);
    log.info("Cluster {} renamed to {}", clusterId, newName);
}
```

Inject `StudentRepository`:
```java
private final StudentRepository studentRepository;
// Add to constructor params
```

- [ ] **Step 2: Update listPendingClusters() to populate new VO fields**

```java
public List<FaceClusterVO> listPendingClusters(Long classId, String status) {
    List<FaceCluster> clusters = clusterRepository
            .findByClassIdAndStatusOrderBySampleCountDesc(classId, status);
    List<FaceClusterVO> result = new ArrayList<>();
    for (FaceCluster c : clusters) {
        FaceClusterVO vo = new FaceClusterVO();
        vo.setId(c.getId());
        vo.setClassId(c.getClassId());
        vo.setSampleCount(c.getSampleCount());
        vo.setFirstSeenAt(c.getFirstSeenAt());
        vo.setLastSeenAt(c.getLastSeenAt());
        vo.setAutoAnnotated(c.getStudentId() != null);
        vo.setStudentId(c.getStudentId());
        // Look up student info if associated
        if (c.getStudentId() != null) {
            studentRepository.findById(c.getStudentId()).ifPresent(s -> {
                vo.setStudentName(s.getName());
                vo.setStudentNo(s.getStudentNo());
            });
        }
        result.add(vo);
    }
    return result;
}
```

- [ ] **Step 3: Commit**

```bash
git add emotion-platform/src/main/java/com/school/emotion/service/FaceLibraryService.java
git commit -m "feat: add renameCluster and update VO population"
```

---

### Task 6: FaceClusterController — add rename endpoint

**Files:**
- Modify: `emotion-platform/src/main/java/com/school/emotion/controller/FaceClusterController.java`

- [ ] **Step 1: Add rename DTO (inner class or use AnnotateRequest)**

Add a simple inner DTO or use a Map parameter. Simplest approach — use `@RequestBody Map<String, String>`:

```java
@PostMapping("/{id}/rename")
public ResponseEntity<?> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
    String studentName = body.get("studentName");
    if (studentName == null || studentName.isBlank()) {
        return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "studentName is required"));
    }
    faceLibraryService.renameCluster(id, studentName);
    return ResponseEntity.ok(Map.of("code", 0, "message", "renamed"));
}
```

- [ ] **Step 2: Commit**

```bash
git add emotion-platform/src/main/java/com/school/emotion/controller/FaceClusterController.java
git commit -m "feat: add rename endpoint for face clusters"
```

---

### Task 7: ExternalEmotionPushClient + DTOs

**Files:**
- Create: `emotion-platform/src/main/java/com/school/emotion/client/ExternalEmotionPushClient.java`
- Create: `emotion-platform/src/main/java/com/school/emotion/client/ExternalEmotionPushRecord.java`

- [ ] **Step 1: Create ExternalEmotionPushRecord DTO**

```java
package com.school.emotion.client;

import java.util.List;

/**
 * Single emotion record for external push AddEmotion API.
 */
public class ExternalEmotionPushRecord {
    private Long Id;
    private String CameraCode;
    private String student_code;
    private String SmallPic;
    private String CaptureTime;
    private String ImageUrl;
    private String Confidence;
    private Integer score;
    private String color;
    private String Emotion;
    private String GazeDirection;
    private String created_at;

    // Getters and setters
    public Long getId() { return Id; }
    public void setId(Long id) { Id = id; }
    public String getCameraCode() { return CameraCode; }
    public void setCameraCode(String cameraCode) { CameraCode = cameraCode; }
    public String getStudent_code() { return student_code; }
    public void setStudent_code(String student_code) { this.student_code = student_code; }
    public String getSmallPic() { return SmallPic; }
    public void setSmallPic(String smallPic) { SmallPic = smallPic; }
    public String getCaptureTime() { return CaptureTime; }
    public void setCaptureTime(String captureTime) { CaptureTime = captureTime; }
    public String getImageUrl() { return ImageUrl; }
    public void setImageUrl(String imageUrl) { ImageUrl = imageUrl; }
    public String getConfidence() { return Confidence; }
    public void setConfidence(String confidence) { Confidence = confidence; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getEmotion() { return Emotion; }
    public void setEmotion(String emotion) { Emotion = emotion; }
    public String getGazeDirection() { return GazeDirection; }
    public void setGazeDirection(String gazeDirection) { GazeDirection = gazeDirection; }
    public String getCreated_at() { return created_at; }
    public void setCreated_at(String created_at) { this.created_at = created_at; }
}
```

- [ ] **Step 2: Add PushResult inner class to ExternalEmotionPushClient**

```java
package com.school.emotion.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class ExternalEmotionPushClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalEmotionPushClient.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String pageId;
    private final String cameraCode;
    private final boolean enabled;

    public ExternalEmotionPushClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.external-push.base-url:http://ylcs.htface.cn:33895}") String baseUrl,
            @Value("${app.external-push.page-id:Emotion}") String pageId,
            @Value("${app.external-push.camera-code:CAM_DEFAULT}") String cameraCode,
            @Value("${app.external-push.enabled:false}") boolean enabled) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.pageId = pageId;
        this.cameraCode = cameraCode;
        this.enabled = enabled;
    }

    /**
     * Push student info: POST /api/Page/Execute { method: "updateStudent", ... }
     */
    public PushResult updateStudent(String studentCode, String studentName, List<String> imageUrls) {
        if (!enabled) {
            log.debug("External push disabled, skipping updateStudent for {}", studentCode);
            return new PushResult(true, "disabled");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("pageID", pageId);
            body.put("method", "updateStudent");
            body.put("student_code", studentCode);
            body.put("student_name", studentName);
            body.put("ImageUrl", imageUrls);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var response = restTemplate.exchange(
                    baseUrl + "/api/Page/Execute",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class);
            Map<String, Object> resp = response.getBody();
            boolean success = resp != null && Boolean.TRUE.equals(resp.get("success"));
            if (!success) {
                String error = resp != null ? (String) resp.getOrDefault("error", "unknown") : "no response";
                log.warn("External push updateStudent failed for {}: {}", studentCode, error);
                return new PushResult(false, error);
            }
            return new PushResult(true, "ok");
        } catch (Exception e) {
            log.warn("External push updateStudent error for {}: {}", studentCode, e.getMessage());
            return new PushResult(false, e.getMessage());
        }
    }

    /**
     * Batch push emotion records: POST /api/Page/Execute { method: "AddEmotion", emotions: [...] }
     */
    public PushResult addEmotions(List<ExternalEmotionPushRecord> emotions) {
        if (!enabled || emotions.isEmpty()) {
            return new PushResult(true, "disabled or empty");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("pageID", pageId);
            body.put("method", "AddEmotion");
            body.put("emotions", emotions);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var response = restTemplate.exchange(
                    baseUrl + "/api/Page/Execute",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class);
            Map<String, Object> resp = response.getBody();
            if (resp != null && Boolean.TRUE.equals(resp.get("success"))) {
                Object data = resp.get("data");
                log.info("External push AddEmotion success: {}", data);
                return new PushResult(true, "ok");
            } else {
                String error = resp != null ? (String) resp.getOrDefault("error", "unknown") : "no response";
                log.warn("External push AddEmotion failed: {}", error);
                return new PushResult(false, error);
            }
        } catch (Exception e) {
            log.warn("External push AddEmotion error: {}", e.getMessage());
            return new PushResult(false, e.getMessage());
        }
    }

    public static class PushResult {
        private final boolean success;
        private final String message;
        public PushResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add emotion-platform/src/main/java/com/school/emotion/client/ExternalEmotionPushClient.java
git add emotion-platform/src/main/java/com/school/emotion/client/ExternalEmotionPushRecord.java
git commit -m "feat: add ExternalEmotionPushClient and DTO"
```

---

### Task 8: ExternalEmotionPushService — business logic + label mapping

**Files:**
- Create: `emotion-platform/src/main/java/com/school/emotion/service/ExternalEmotionPushService.java`

- [ ] **Step 1: Create ExternalEmotionPushService**

```java
package com.school.emotion.service;

import com.school.emotion.client.ExternalEmotionPushClient;
import com.school.emotion.client.ExternalEmotionPushRecord;
import com.school.emotion.model.entity.*;
import com.school.emotion.repository.EmotionRecordRepository;
import com.school.emotion.repository.FaceClusterRepository;
import com.school.emotion.repository.FaceRecordRepository;
import com.school.emotion.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExternalEmotionPushService {

    private static final Logger log = LoggerFactory.getLogger(ExternalEmotionPushService.class);
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ExternalEmotionPushClient pushClient;
    private final StudentRepository studentRepository;
    private final FaceRecordRepository faceRecordRepository;
    private final EmotionRecordRepository emotionRecordRepository;
    private final FaceClusterRepository clusterRepository;

    public ExternalEmotionPushService(
            ExternalEmotionPushClient pushClient,
            StudentRepository studentRepository,
            FaceRecordRepository faceRecordRepository,
            EmotionRecordRepository emotionRecordRepository,
            FaceClusterRepository clusterRepository) {
        this.pushClient = pushClient;
        this.studentRepository = studentRepository;
        this.faceRecordRepository = faceRecordRepository;
        this.emotionRecordRepository = emotionRecordRepository;
        this.clusterRepository = clusterRepository;
    }

    /**
     * Push a single student's info to external system.
     * Collects face image URLs from face_records associated with this student.
     */
    public void pushStudent(Student student) {
        List<String> imageUrls = faceRecordRepository.findByStudentId(student.getId()).stream()
                .filter(fr -> fr.getCroppedImageUrl() != null)
                .map(FaceRecord::getCroppedImageUrl)
                .limit(5)
                .collect(Collectors.toList());

        var result = pushClient.updateStudent(student.getStudentNo(), student.getName(), imageUrls);
        if (result.isSuccess()) {
            log.debug("Pushed student {} ({})", student.getStudentNo(), student.getName());
        } else {
            log.warn("Failed to push student {}: {}", student.getStudentNo(), result.getMessage());
        }
    }

    /**
     * Push all students to external system.
     */
    public void pushAllStudents() {
        List<Student> students = studentRepository.findAll();
        int pushed = 0, failed = 0;
        for (Student s : students) {
            var result = pushStudentAndGetResult(s);
            if (result.isSuccess()) pushed++;
            else failed++;
        }
        log.info("Push all students: {} pushed, {} failed", pushed, failed);
    }

    private ExternalEmotionPushClient.PushResult pushStudentAndGetResult(Student student) {
        List<String> imageUrls = faceRecordRepository.findByStudentId(student.getId()).stream()
                .filter(fr -> fr.getCroppedImageUrl() != null)
                .map(FaceRecord::getCroppedImageUrl)
                .limit(5)
                .collect(Collectors.toList());
        return pushClient.updateStudent(student.getStudentNo(), student.getName(), imageUrls);
    }

    /**
     * Push all emotion records to external system.
     */
    public void pushAllEmotions() {
        List<EmotionRecord> records = emotionRecordRepository.findAll();
        if (records.isEmpty()) {
            log.info("No emotion records to push");
            return;
        }
        pushEmotionRecords(records);
    }

    /**
     * Convert and push a batch of EmotionRecords.
     */
    public void pushEmotionRecords(List<EmotionRecord> records) {
        List<ExternalEmotionPushRecord> pushRecords = records.stream()
                .map(this::toPushRecord)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (pushRecords.isEmpty()) return;

        // Batch in chunks of 200
        int batchSize = 200;
        for (int i = 0; i < pushRecords.size(); i += batchSize) {
            List<ExternalEmotionPushRecord> batch = pushRecords.subList(i,
                    Math.min(i + batchSize, pushRecords.size()));
            var result = pushClient.addEmotions(batch);
            if (!result.isSuccess()) {
                log.warn("Failed to push emotion batch {}-{}: {}", i, i + batch.size(), result.getMessage());
            }
        }
    }

    /**
     * Map EmotionRecord → ExternalEmotionPushRecord.
     * Uses dominantEmotion (Chinese label) to look up external Emotion code.
     */
    private ExternalEmotionPushRecord toPushRecord(EmotionRecord er) {
        try {
            FaceRecord fr = er.getFaceRecord();
            if (fr == null) return null;

            Student student = fr.getStudent();
            if (student == null) return null;

            ClassImage ci = fr.getClassImage();
            if (ci == null) return null;

            ExternalEmotionPushRecord record = new ExternalEmotionPushRecord();
            record.setId(er.getId());
            record.setCameraCode(pushClient.getCameraCode());
            record.setStudent_code(student.getStudentNo());
            record.setSmallPic(fr.getCroppedImageUrl());
            record.setCaptureTime(ci.getCaptureTime() != null
                    ? ci.getCaptureTime().format(DTF) : "");
            record.setImageUrl(ci.getImageUrl());
            record.setConfidence(er.getDominantConfidence() != null
                    ? String.format("%.2f", er.getDominantConfidence()) : "0.00");
            record.setScore(er.getDominantConfidence() != null
                    ? Math.round(er.getDominantConfidence() * 100) : 0);

            // Map dominant emotion label
            String externalEmotion = mapEmotion(er.getDominantEmotion());
            record.setEmotion(externalEmotion);
            record.setColor(mapColor(externalEmotion));
            record.setGazeDirection("");
            record.setCreated_at(er.getCreatedAt() != null
                    ? er.getCreatedAt().format(DTF) : "");

            return record;
        } catch (Exception e) {
            log.warn("Failed to convert EmotionRecord {}: {}", er.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Map Chinese emotion label from engine to external system Emotion code.
     */
    private String mapEmotion(String chineseLabel) {
        if (chineseLabel == null) return "calm";
        return switch (chineseLabel) {
            case "开心" -> "happy";
            case "伤心" -> "sad";
            case "愤怒" -> "angry";
            case "惊讶" -> "surprised";
            case "恐惧" -> "fearful";
            case "中性" -> "calm";
            case "蔑视" -> "calm";
            case "厌恶" -> "angry";
            default -> "calm";
        };
    }

    /**
     * Map external Emotion code to color string.
     */
    private String mapColor(String emotion) {
        return switch (emotion) {
            case "happy" -> "green";
            case "sad" -> "blue";
            case "angry" -> "red";
            case "calm" -> "cyan";
            case "surprised" -> "yellow";
            case "fearful" -> "purple";
            default -> "";
        };
    }

    /**
     * Push start count (for manual trigger reporting).
     */
    public PushSummary pushAll() {
        pushAllStudents();
        pushAllEmotions();
        return new PushSummary(0, 0); // Simplified — actual counts from each method
    }

    public record PushSummary(int pushed, int failed) {}
}
```

Add a getter to ExternalEmotionPushClient:
```java
public String getCameraCode() { return cameraCode; }
```

- [ ] **Step 2: Commit**

```bash
git add emotion-platform/src/main/java/com/school/emotion/service/ExternalEmotionPushService.java
git add emotion-platform/src/main/java/com/school/emotion/client/ExternalEmotionPushClient.java
git commit -m "feat: add ExternalEmotionPushService with label mapping"
```

---

### Task 9: Application config + ExternalPushController

**Files:**
- Modify: `emotion-platform/src/main/resources/application.yml`
- Create: `emotion-platform/src/main/java/com/school/emotion/controller/ExternalPushController.java`

- [ ] **Step 1: Add external-push config to application.yml**

```yaml
app:
  external-push:
    enabled: false                           # 总开关，默认关闭
    base-url: http://ylcs.htface.cn:33895    # 外部系统地址
    page-id: Emotion                         # 固定 pageID
    camera-code: CAM_DEFAULT                 # 摄像头编码
    batch-size: 200                          # AddEmotion 批量上限
```

Append under existing `app:` section.

- [ ] **Step 2: Create ExternalPushController**

```java
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

    /**
     * Manual trigger: POST /api/v1/admin/external-push?type=students|emotions|all
     */
    @PostMapping
    public ResponseEntity<?> push(
            @RequestParam(defaultValue = "all") String type) {
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
```

- [ ] **Step 3: Wire pushStudent into FaceClusteringServiceV2 autoAnnotateClusters()**

Modify the end of `autoAnnotateClusters()` — after saving cluster, push the student:

```java
// After clusterRepository.save(cluster):
// Push student to external system if enabled
try {
    ExternalEmotionPushService pushService = applicationContext.getBean(ExternalEmotionPushService.class);
    pushService.pushStudent(student);
} catch (Exception pushEx) {
    log.warn("Failed to push student {} after auto-annotate: {}", student.getStudentNo(), pushEx.getMessage());
}
```

Add `ApplicationContext` injection to FaceClusteringServiceV2:
```java
import org.springframework.context.ApplicationContext;
// In constructor inject:
private final ApplicationContext applicationContext;
```

Or better — inject `ExternalEmotionPushService` directly (with `@Lazy` or `@Autowired(required=false)`):

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

// Add field
@Autowired(required = false)
@Lazy
private ExternalEmotionPushService externalPushService;
```

In `autoAnnotateClusters()`, after cluster save:
```java
if (externalPushService != null) {
    try {
        externalPushService.pushStudent(student);
    } catch (Exception e) {
        log.warn("Failed to push student after auto-annotate: {}", e.getMessage());
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add emotion-platform/src/main/resources/application.yml
git add emotion-platform/src/main/java/com/school/emotion/controller/ExternalPushController.java
git add emotion-platform/src/main/java/com/school/emotion/service/FaceClusteringServiceV2.java
git commit -m "feat: add external push controller and wire push to clustering"
```

---

### Task 10: Frontend — add rename API call

**Files:**
- Modify: `emotion-frontend/src/api/admin.ts`

- [ ] **Step 1: Add renameCluster function**

```typescript
export function renameCluster(id: number, studentName: string): Promise<void> {
  return client.post(`/face-clusters/${id}/rename`, { studentName })
}
```

- [ ] **Step 2: Commit**

```bash
git add emotion-frontend/src/api/admin.ts
git commit -m "feat: add renameCluster API call"
```

---

### Task 11: Frontend — FaceClusterPage.vue show default name + rename

**Files:**
- Modify: `emotion-frontend/src/views/FaceClusterPage.vue`

- [ ] **Step 1: Update table to show student name and add rename button**

Replace the template's `<el-table>` columns:
- Change the "操作" column to show "重命名" instead of "标注"
- Add a column for student name
- Add a rename dialog

```vue
<template>
  <div class="face-cluster-page">
    <div class="page-header">
      <h2>人脸聚类标注</h2>
      <div class="header-actions">
        <el-select v-model="classId" placeholder="选择班级" @change="loadData" size="small">
          <el-option label="初一班" :value="1" />
          <el-option label="初二(1)班" :value="2" />
          <el-option label="初二(2)班" :value="3" />
        </el-select>
        <el-tag type="warning">待标注: {{ clusters.length }}</el-tag>
      </div>
    </div>

    <el-table :data="clusters" style="width:100%" stripe>
      <el-table-column label="学生姓名" width="130">
        <template #default="{ row }">
          <span :style="{ color: row.autoAnnotated ? '#909399' : '#409EFF' }">
            {{ row.studentName || '未标注' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="学号" width="150">
        <template #default="{ row }">
          {{ row.studentNo || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="sampleCount" label="出现次数" width="100" sortable />
      <el-table-column label="首次出现" width="160">
        <template #default="{ row }">{{ formatTime(row.firstSeenAt) }}</template>
      </el-table-column>
      <el-table-column label="最近出现" width="160">
        <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openRename(row)">重命名</el-button>
          <el-button size="small" @click="openMerge(row)">合并</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 重命名对话框 -->
    <el-dialog v-model="showRename" title="重命名学生" width="360px">
      <el-form :model="renameForm" label-width="80px">
        <el-form-item label="当前名称">
          <el-input :model-value="renameForm.currentName" disabled />
        </el-form-item>
        <el-form-item label="新名称" required>
          <el-input v-model="renameForm.newName" placeholder="输入真实姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showRename = false">取消</el-button>
        <el-button type="primary" @click="submitRename">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchClusters, renameCluster, type FaceClusterVO } from '@/api/admin'
import { ElMessage } from 'element-plus'

const clusters = ref<FaceClusterVO[]>([])
const classId = ref(1)
const showRename = ref(false)
const selectedCluster = ref<FaceClusterVO | null>(null)
const renameForm = ref({ currentName: '', newName: '' })

onMounted(() => loadData())

async function loadData() {
  try {
    clusters.value = await fetchClusters(classId.value)
  } catch { /* ignore */ }
}

function formatTime(ts: string) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}

function openRename(cluster: FaceClusterVO) {
  selectedCluster.value = cluster
  renameForm.value = {
    currentName: cluster.studentName || '未命名',
    newName: cluster.studentName || ''
  }
  showRename.value = true
}

async function submitRename() {
  if (!selectedCluster.value || !renameForm.value.newName.trim()) {
    ElMessage.warning('请输入新名称')
    return
  }
  try {
    await renameCluster(selectedCluster.value.id, renameForm.value.newName.trim())
    ElMessage.success('重命名成功')
    showRename.value = false
    loadData()
  } catch { ElMessage.error('重命名失败') }
}

function openMerge(cluster: FaceClusterVO) {
  ElMessage.info('合并功能开发中')
}
</script>

<style scoped>
.face-cluster-page { padding: 0; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-4); }
.page-header h2 { font-size: var(--text-xl); font-weight: 600; }
.header-actions { display: flex; gap: var(--space-3); align-items: center; }
</style>
```

- [ ] **Step 2: Update FaceClusterVO type in admin.ts**

```typescript
export interface FaceClusterVO {
  id: number; classId: number; className?: string; sampleCount: number
  firstSeenAt: string; lastSeenAt: string; periodLabels?: string[]; sampleImages?: string[]
  studentId?: number; studentName?: string; studentNo?: string; autoAnnotated?: boolean
}
```

- [ ] **Step 3: Commit**

```bash
git add emotion-frontend/src/views/FaceClusterPage.vue
git add emotion-frontend/src/api/admin.ts
git commit -m "feat: update FaceClusterPage with student name display and rename"
```

---

### Task 12: Verification

- [ ] **Step 1: Verify backend compiles**

```bash
cd emotion-platform && mvn compile -q 2>&1 | tail -20
```
Expected: `BUILD SUCCESS`

- [ ] **Step 2: Verify frontend compiles**

```bash
cd emotion-frontend && npx vue-tsc --noEmit 2>&1 | tail -20
```
Expected: no type errors

- [ ] **Step 3: Run backend tests**

```bash
cd emotion-platform && mvn test -q 2>&1 | tail -20
```
Expected: all tests pass
