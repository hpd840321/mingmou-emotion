# Pipeline Issue Fix — 全量修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 REST→gRPC 管线中发现的 15 个问题，P0/P1/P2/P3 分 4 个阶段逐个解决

**Architecture:** 4 个独立阶段：功能断裂修复 → 数据正确性 → 数据质量与测试 → 代码清理。每阶段完成后可独立验证，后阶段依赖前阶段的管线正常工作。

**Tech Stack:** Spring Boot 3, Redis Stream, gRPC, JPA/Hibernate, Resilience4j, JUnit 5

**Base path:** `emotion-platform/src/main/java/com/school/emotion/`

---

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `config/RedisStreamConfig.java` | 修改 | 注册消费者到 `image:ingest` |
| `config/AsyncConfig.java` | **创建** | 配置 `@Async` 线程池 |
| `config/DataScanRunner.java` | 修改 | 改用 `@Async` |
| `controller/AdminController.java` | 修改 | 改用 `@Async` |
| `controller/ClassController.java` | 修改 | heatmap 按 classId 过滤 |
| `service/ImageProcessingOrchestrator.java` | 修改 | 添加 `@Async` 适配 |
| `service/FaceProcessingPipeline.java` | 修改 | 删除 Student 自动创建，改用 VisionMindClient 统一路径 |
| `service/ImageProcessingPersistenceService.java` | 修改 | 保存 Emotion 维度概率 |
| `service/FaceClusteringService.java` | 修改 | 移除 `@Scheduled` 或添加 `@ConditionalOnProperty` |
| `service/FaceCroppingService.java` | 修改 | 无改动（P3） |
| `model/dto/FaceDetectionResult.java` | 修改 | BBox int→float |
| `client/VisionMindClient.java` | 修改 | 统一字段命名 |
| `service/ImageIngestionService.java` | 修改 | 添加 `@Async` 适配 |
| `config/RedisStreamConfig.java` | 修改 | 注册 StreamListener |
| 测试文件 | **创建/修改** | 补充关键测试 |

---

## Phase 1 — P0 功能断裂修复

### Task 1: Redis Stream 消费者注册到 image:ingest

**Files:**
- Create: `service/ImageIngestConsumer.java`
- Modify: `config/RedisStreamConfig.java`

**背景:** `DataDirectoryScanner`, `ImageImportService`, `ImageIngestionService` 三处写入 Redis stream `image:ingest`，但无消费者读取。现有 `StreamMessageListenerContainer` bean 未被使用。

- [ ] **Step 1: 创建 ImageIngestConsumer — 注册 StreamListener**

```java
package com.school.emotion.service;

import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.ClassImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class ImageIngestConsumer implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImageIngestConsumer.class);

    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final ClassImageRepository classImageRepository;
    private final ImageProcessingOrchestrator orchestrator;

    public ImageIngestConsumer(
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            ClassImageRepository classImageRepository,
            ImageProcessingOrchestrator orchestrator) {
        this.container = container;
        this.classImageRepository = classImageRepository;
        this.orchestrator = orchestrator;
    }

    @Override
    public void afterPropertiesSet() {
        try {
            container.receive(
                    Consumer.from("image-processors", "processor-1"),
                    StreamOffset.create("image:ingest", ReadOffset.lastConsumed()),
                    msg -> {
                        String imageId = msg.getValue().get("imageId");
                        if (imageId == null) return;
                        log.debug("Received image:ingest message: {}", imageId);
                        classImageRepository.findById(Long.parseLong(imageId))
                                .filter(ci -> ci.getStatus() == ImageStatus.PENDING)
                                .ifPresent(ci -> {
                                    try {
                                        orchestrator.processImage(ci);
                                    } catch (Exception e) {
                                        log.error("Failed to process image {} from stream: {}", imageId, e.getMessage());
                                    }
                                });
                    });
            container.start();
            log.info("ImageIngestConsumer subscribed to image:ingest stream");
        } catch (Exception e) {
            log.error("Failed to register Redis stream consumer: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        try {
            container.stop();
        } catch (Exception e) {
            // ignore on shutdown
        }
    }
}
```

- [ ] **Step 2: 修改 RedisStreamConfig — 暴露 StreamReference 并配置正确的序列化**

```java
// RedisStreamConfig.java — 修改 streamContainer bean，添加 key/value 序列化
var options = StreamMessageListenerContainerOptions
        .builder()
        .pollTimeout(Duration.ofSeconds(1))
        .targetType(String.class)  // 添加这一行
        .build();
```

- [ ] **Step 3: 验证编译通过**

```bash
cd /home/zebra/Downloads/官渡一中初一班-0526/emotion-platform
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "fix: add Redis stream consumer for image:ingest pipeline"
```

---

### Task 2: 异步任务生命周期管理

**Files:**
- Create: `config/AsyncConfig.java`
- Modify: `config/DataScanRunner.java`
- Modify: `controller/AdminController.java`
- Modify: `service/ImageIngestionService.java`

**背景:** `CompletableFuture.runAsync()` 使用公共 ForkJoinPool，`CommandLineRunner` 返回后 JVM 退出导致异步任务被中断。

- [ ] **Step 1: 创建 AsyncConfig**

```java
package com.school.emotion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("pipelineExecutor")
    public TaskExecutor pipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(120);
        executor.setThreadNamePrefix("pipeline-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 2: 修改 DataScanRunner — 改用 @Async**

```java
// DataScanRunner.java — 注入 pipelineExecutor
// 删除 CommandLineRunner 内的 CompletableFuture.runAsync，改为在 ApplicationRunner 中同步或使用 @Async
@Component
@ConditionalOnProperty(name = "app.scan.auto", havingValue = "true", matchIfMissing = false)
public class DataScanRunner implements CommandLineRunner {
    // ... 其他不变

    @Override
    public void run(String... args) {
        log.info("Starting automatic data directory scan...");
        long start = System.currentTimeMillis();
        var report = scanner.scanAll();
        long elapsed = (System.currentTimeMillis() - start) / 1000;
        if (report.error() != null) {
            log.warn("Scan completed with errors: {}", report.error());
        }
        log.info("Scan complete in {}s: {} total images, {} imported ({} skipped/failed)",
                elapsed, report.total(), report.imported(), report.total() - report.imported());
    }
}
```

- [ ] **Step 3: 修改 AdminController — 改用 @Async**

```java
// AdminController.java — 注入 pipelineExecutor + 改为 @Async
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    // ... 其他不变

    @Async("pipelineExecutor")
    @PostMapping("/pipeline/run")
    public CompletableFuture<ResponseEntity<?>> runPipeline() {
        var report = pipeline.processAll();
        log.info("Pipeline finished: total={}, detected={}, noFace={}, errors={}, time={}s",
                report.total(), report.detected(), report.noFace(), report.errors(), report.elapsedSeconds());
        return CompletableFuture.completedFuture(ResponseEntity.accepted().body(Map.of(
                "code", 0, "message", "Pipeline started in background")));
    }

    @Async("pipelineExecutor")
    @PostMapping("/scan")
    public CompletableFuture<ResponseEntity<?>> scanAll() {
        var report = scanner.scanAll();
        return CompletableFuture.completedFuture(ResponseEntity.accepted().body(Map.of(
                "code", 0, "message", "scan completed")));
    }
}
```

- [ ] **Step 4: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "fix: replace CompletableFuture.runAsync with @Async + dedicated thread pool"
```

---

## Phase 2 — P1 数据正确性修复

### Task 3: 合并两套 Pipeline (删除 ImageProcessingOrchestrator)

**Files:**
- Modify: `service/FaceProcessingPipeline.java`
- Delete: `service/ImageProcessingOrchestrator.java` (或废弃)
- Delete: `service/ImageProcessingPersistenceService.java` (或废弃)
- Modify: `service/ai/FaceRecognitionClient.java`
- Modify: `service/ai/EmotionRecognitionClient.java`

**背景:** `ImageProcessingOrchestrator` 和 `FaceProcessingPipeline` 做相同的事。保留功能更全的 `FaceProcessingPipeline`（有抠图+注册），删除 `ImageProcessingOrchestrator`。

注意：Task 1 中 `ImageIngestConsumer` 引用了 `orchestrator.processImage()`。如果删除 `ImageProcessingOrchestrator`，需要把 `processImage` 方法迁移到 `FaceProcessingPipeline` 或新的统一类中。

**方案：**将 `ImageProcessingOrchestrator.processImage()` 的核心逻辑迁移合并到 `FaceProcessingPipeline`，`ImageIngestConsumer` 改为调用 `FaceProcessingPipeline.processImage()`。

- [ ] **Step 1: 在 FaceProcessingPipeline 中暴露 processImage 方法对接 ImageIngestConsumer**

`FaceProcessingPipeline.processImage(ClassImage)` 已经存在（第 97 行），但它是 `@Transactional` 且内部会创建 Student。做两件事：将 `@Transactional` 移到 `processAll()` 级别的批量事务上，提取一个内部处理方法 `processSingleImage` 供外部调用：

```java
// FaceProcessingPipeline.java
// 将 @Transactional 从 processImage 移到更细粒度的持久化操作上
// 添加公共入口方法:

/**
 * 单图处理入口（供 ImageIngestConsumer 调用）。
 * 与 processAll() 共享核心逻辑但不创建自动 Student。
 */
public ProcessResult processSingleImage(ClassImage ci) {
    // 调用现有 processImage 内部逻辑
    return processImage(ci);
}
```

注意: `processImage()` 内部创建 Student 的部分将在 Task 6 中移除。在 Task 6 完成前，`processSingleImage` 的行为与现有 `processImage` 相同。

- [ ] **Step 2: 修改 ImageIngestConsumer，将 orchestrator 替换为 faceProcessingPipeline**

```java
// ImageIngestConsumer.java — 注入 FaceProcessingPipeline 替换 ImageProcessingOrchestrator
private final FaceProcessingPipeline pipeline;

// 在消息回调中:
if (ci.getStatus() == ImageStatus.PENDING) {
    pipeline.processSingleImage(ci);
}
```

注意：需要先在 FaceProcessingPipeline 创建 `processSingleImage` 方法（调 `processImage` 但跳过 Student 创建，等 Task 6 修复）。

- [ ] **Step 3: 标记 ImageProcessingOrchestrator 为 @Deprecated 并禁用其 @Scheduled**

```java
// ImageProcessingOrchestrator.java — 删除 @Scheduled 注解
// 保留类但不再自动轮询
// 将 @Scheduled(fixedDelay = 86400000) 改为空方法或移除注解
```

- [ ] **Step 4: 清理 ImageProcessingPersistenceService — 标记 @Deprecated 并添加注释说明由 FaceProcessingPipeline 替代**

```java
// ImageProcessingPersistenceService.java — 添加 @Deprecated 注解
```

- [ ] **Step 5: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "fix: consolidate dual pipelines into FaceProcessingPipeline, deprecate ImageProcessingOrchestrator"
```

---

### Task 4: ClassController.heatmap() 按 classId 过滤

**Files:**
- Modify: `controller/ClassController.java`

- [ ] **Step 1: 修改 heatmap 方法 — 按 classId 过滤图片**

```java
// ClassController.java — 第 60 行
// 修改前:
var images = classImageRepository.findAll();

// 修改后:
var images = classImageRepository.findByClazz_Id(id);

// 需要确认 ClassImageRepository 有该方法，如没有需要添加
```

打开 `ClassImageRepository.java`：

```java
// ClassImageRepository.java — 添加查询方法
List<ClassImage> findByClazz_Id(Long classId);
```

- [ ] **Step 2: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "fix: filter heatmap by classId instead of returning all images"
```

---

### Task 5: Emotion 维度概率存入 EmotionRecord

**Files:**
- Modify: `dto/EmotionAnalysisResult.java`
- Modify: `service/ImageProcessingPersistenceService.java`
- Modify: `service/FaceProcessingPipeline.java`

- [ ] **Step 1: 确认 EmotionAnalysisResult.getEmotions() 包含维度概率**

```java
// EmotionAnalysisResult.java — 当前结构已包含 Map<String, Float> emotions
// 确认 fromVmResponse 正确解析 emotions 字段
// 在第 38 行 return 之前添加:
if (emotionData.containsKey("probabilities")) {
    Map<String, Float> probs = new HashMap<>();
    // visionmind返回: {"happy": 0.75, "sad": 0.05, ...}
    Object probsObj = emotionData.get("probabilities");
    if (probsObj instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) probsObj;
        for (var entry : raw.entrySet()) {
            if (entry.getValue() instanceof Number) {
                probs.put(entry.getKey(), ((Number) entry.getValue()).floatValue());
            }
        }
    }
    result.setEmotions(probs);
}
```

- [ ] **Step 2: 修改 saveResults — 写入 7 个维度字段**

```java
// ImageProcessingPersistenceService.java — 在 emotionRecord 保存前:
if (emotionResult != null && emotionResult.getDominantEmotion() != null) {
    EmotionRecord emotionRecord = new EmotionRecord();
    emotionRecord.setFaceRecord(faceRecord);
    emotionRecord.setDominantEmotion(emotionResult.getDominantEmotion());
    emotionRecord.setDominantConfidence(emotionResult.getDominantConfidence());

    // 写入 7 个维度概率
    Map<String, Float> probs = emotionResult.getEmotions();
    if (probs != null) {
        emotionRecord.setEmotionHappy(probs.get("happy"));
        emotionRecord.setEmotionSad(probs.get("sad"));
        emotionRecord.setEmotionAngry(probs.get("angry"));
        emotionRecord.setEmotionSurprise(probs.get("surprise"));
        emotionRecord.setEmotionFear(probs.get("fear"));
        emotionRecord.setEmotionDisgust(probs.get("disgust"));
        emotionRecord.setEmotionNeutral(probs.get("neutral"));
    }

    emotionRecordRepository.save(emotionRecord);
    // ...
}
```

同时在 `FaceProcessingPipeline.processImage()` 中做相同处理（第 178-189 行）。

- [ ] **Step 3: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "fix: persist all 7 emotion dimension probabilities to EmotionRecord"
```

---

### Task 6: 修复 Student 自动创建逻辑

**Files:**
- Modify: `service/FaceProcessingPipeline.java`

**背景:** `FaceProcessingPipeline.processImage()` 第 134-140 行为每张检测到的人脸创建 Student 记录，这导致大量无意义的 `AUTO_xxx` Student 记录。该 Student 随后不被任何 EmotionRecord 引用（`faceRecord.setStudent` 为 null）。

- [ ] **Step 1: 删除 Student 自动创建代码**

```java
// FaceProcessingPipeline.java — 删除第 133-140 行（Student 创建）
// 替换为:
// 不自动创建 Student，留待人工标注或 FaceLibraryService 处理
```

- [ ] **Step 2: 将 faceRecord 的 student 设为 null（行为不变，只是移除自动创建）**

```java
// FaceProcessingPipeline.processImage() — 保持 fr.setStudent(null); 或直接不设置
```

- [ ] **Step 3: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "fix: stop auto-creating dummy Student records per face detection"
```

---

## Phase 3 — P2 数据质量与测试

### Task 7: BBox int→float

**Files:**
- Modify: `model/dto/FaceDetectionResult.java`
- Modify: `service/FaceProcessingPipeline.java` (JSON 序列化格式)
- Modify: `service/FaceCroppingService.java` (参数类型)
- Modify: `controller/ImageIngestController.java`（如果有 BBox 参数）

- [ ] **Step 1: 修改 BBox 字段类型 int→float**

```java
// FaceDetectionResult.java — 第 28-30 行
public static class BBox {
    private float x, y, width, height;
    public float getX() { return x; }
    public void setX(float x) { this.x = x; }
    public float getY() { return y; }
    public void setY(float y) { this.y = y; }
    public float getWidth() { return width; }
    public void setWidth(float width) { this.width = width; }
    public float getHeight() { return height; }
    public void setHeight(float height) { this.height = height; }
}
```

- [ ] **Step 2: 修改 fromVmResponse 解析逻辑 — 支持 float BBox**

```java
// FaceDetectionResult.java — 第 47-54 行
List<Number> bboxList = (List<Number>) f.get("bbox");
if (bboxList != null && bboxList.size() == 4) {
    BBox bbox = new BBox();
    bbox.setX(bboxList.get(0).floatValue());
    bbox.setY(bboxList.get(1).floatValue());
    bbox.setWidth(bboxList.get(2).floatValue());
    bbox.setHeight(bboxList.get(3).floatValue());
    face.setBbox(bbox);
}
```

- [ ] **Step 3: 修改 FaceProcessingPipeline 中 BBox 的 JSON 序列化格式**

```java
// FaceProcessingPipeline.java — 第 147-148 行
// 修改前: String.format("{\"x\":%d,\"y\":%d,\"width\":%d,\"height\":%d}", ...)
// 修改后: 使用 ObjectMapper 序列化
String bboxJson = objectMapper.writeValueAsString(bestFace.getBbox());
fr.setBbox(bboxJson);
```

- [ ] **Step 4: 在 FaceCroppingService 的参数签名中确认 float 兼容性**

`FaceCroppingService.cropFace()` 的参数是 `int x, int y, int w, int h`，调用处需要适配 float→int 转换：

```java
// FaceProcessingPipeline.java — 调用 cropFace 前
int cropX = Math.round(bbox != null ? bbox.getX() : 0);
int cropY = Math.round(bbox != null ? bbox.getY() : 0);
int cropW = Math.round(bbox != null ? bbox.getWidth() : 0);
int cropH = Math.round(bbox != null ? bbox.getHeight() : 0);
var cropResult = croppingService.cropFace(
        imagePath, cropX, cropY, cropW, cropH, ...);
```

- [ ] **Step 5: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "fix: change BBox from int to float to match gRPC proto precision"
```

---

### Task 8: 禁用 FaceClusteringService V1（基于 token 前缀的旧聚类）

**Files:**
- Modify: `service/ai/FaceClusteringService.java`

**背景:** V1 按 token 前 8 字符哈希聚类，不准确。V2（FaceClusteringServiceV2）使用 Qdrant 向量 + 余弦相似度才是正确的。V1 仍在通过 `@Scheduled(fixedRate = 30000)` 每 30 秒运行。

- [ ] **Step 1: 禁用 V1 的 @Scheduled**

```java
// FaceClusteringService.java — 注释掉或删除 @Scheduled
// @Scheduled(fixedRate = 30000)  // ← 注释掉这行
public void processPendingClusters() {
    // 方法体保留但不自动调用
}
```

或添加配置开关：

```java
// FaceClusteringService.java — 使用 @ConditionalOnProperty 禁用
@Component
@ConditionalOnProperty(name = "app.clustering.v1.enabled", havingValue = "false", matchIfMissing = true)
// 实际上设为 matchIfMissing=false 即默认禁用
// 或者直接移除 @Scheduled，方法体保留供手动调用
```

- [ ] **Step 2: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "chore: disable legacy token-prefix clustering, V2 (Qdrant-based) is the active path"
```

---

### Task 9: 补充关键测试

**Files:**
- Create: `src/test/java/com/school/emotion/service/FaceProcessingPipelineTest.java`
- Create: `src/test/java/com/school/emotion/service/FaceCroppingServiceTest.java`
- Modify: `src/test/java/com/school/emotion/client/VisionMindClientTest.java`

- [ ] **Step 1: 创建 FaceProcessingPipelineTest — 测试单图处理流程**

```java
package com.school.emotion.service;

import com.school.emotion.client.VisionMindClient;
import com.school.emotion.model.dto.EmotionAnalysisResult;
import com.school.emotion.model.dto.FaceDetectionResult;
import com.school.emotion.model.entity.ClassImage;
import com.school.emotion.model.entity.Grade;
import com.school.emotion.model.entity.SchoolClass;
import com.school.emotion.model.enums.ImageStatus;
import com.school.emotion.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FaceProcessingPipelineTest {

    @Mock private ClassImageRepository classImageRepository;
    @Mock private FaceRecordRepository faceRecordRepository;
    @Mock private EmotionRecordRepository emotionRecordRepository;
    @Mock private GradeRepository gradeRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private VisionMindClient visionMindClient;
    @Mock private FaceCroppingService croppingService;
    @Mock private FaceRegistrationService registrationService;

    private FaceProcessingPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new FaceProcessingPipeline(
                classImageRepository, faceRecordRepository, emotionRecordRepository,
                gradeRepository, studentRepository, visionMindClient,
                croppingService, registrationService, 0.3f, 50);
    }

    @Test
    void processImage_noFaces_marksCompleted() {
        ClassImage ci = new ClassImage();
        ci.setId(1L);
        ci.setImageUrl("/nonexistent.jpg");
        ci.setStatus(ImageStatus.PENDING);

        when(visionMindClient.detectFaces(any())).thenReturn(new FaceDetectionResult());

        // 图片不存在会抛异常，验证异常处理
        assertDoesNotThrow(() -> pipeline.processImage(ci));
    }

    @Test
    void processImage_detectionError_marksFailed() {
        ClassImage ci = new ClassImage();
        ci.setId(1L);
        ci.setImageUrl("/nonexistent.jpg");
        ci.setStatus(ImageStatus.PENDING);

        when(visionMindClient.detectFaces(any())).thenThrow(
                new RuntimeException("API unavailable"));

        assertDoesNotThrow(() -> pipeline.processImage(ci));
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
cd /home/zebra/Downloads/官渡一中初一班-0526/emotion-platform
mvn test -pl . -Dtest=FaceProcessingPipelineTest -DfailIfNoTests=false 2>&1 | tail -30
```
Expected: Tests run: 2, Failures: 0

- [ ] **Step 3: 补充 VisionMindClientTest — 添加 BBox float 解析测试**

```java
// VisionMindClientTest.java — 添加新测试
@Test
void detectFaces_shouldParseFloatBbox() throws Exception {
    String base64 = Base64.getEncoder().encodeToString("test-image".getBytes());
    Map<String, Object> mockResponse = Map.of(
            "code", 0, "message", "success",
            "data", Map.of("faces", new Object[]{
                    Map.of("bbox", List.of(10.5, 20.3, 100.7, 150.2), "confidence", 0.95)
            }));

    mockServer.expect(requestTo("http://localhost:8080/v1/face/detect"))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andRespond(withSuccess(objectMapper.writeValueAsString(mockResponse), MediaType.APPLICATION_JSON));

    FaceDetectionResult result = client.detectFaces("test-image".getBytes());
    assertNotNull(result.getFaces().get(0).getBbox());
    assertEquals(10.5f, result.getFaces().get(0).getBbox().getX(), 0.01);
    assertEquals(100.7f, result.getFaces().get(0).getBbox().getWidth(), 0.01);
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=VisionMindClientTest -DfailIfNoTests=false 2>&1 | tail -30
```
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "test: add pipeline unit tests and BBox float parsing coverage"
```

---

## Phase 4 — P3 代码质量

### Task 10: 清理 FaceClusteringService 的 JSON 字符串拼接

**Files:**
- Modify: `service/ai/FaceClusteringService.java`

- [ ] **Step 1: 将 parseTokenList 和 toJsonArray 改为 Jackson 操作**

```java
// FaceClusteringService.java — 添加 ObjectMapper 依赖
private final ObjectMapper objectMapper = new ObjectMapper();

private List<String> parseTokenList(String json) {
    try {
        String[] tokens = objectMapper.readValue(json, String[].class);
        return new ArrayList<>(Arrays.asList(tokens));
    } catch (Exception e) {
        log.warn("Failed to parse face tokens JSON: {}", json, e);
        return new ArrayList<>();
    }
}

private String toJsonArray(List<String> tokens) {
    try {
        return objectMapper.writeValueAsString(tokens);
    } catch (Exception e) {
        log.warn("Failed to serialize face tokens", e);
        return "[]";
    }
}
```

- [ ] **Step 2: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "refactor: use ObjectMapper for JSON instead of fragile string manipulation"
```

---

### Task 11: 统一 API 字段命名规范

**Files:**
- Modify: `client/VisionMindClient.java`

- [ ] **Step 1: 统一 searchFaces 的字段名为 image_base64（与 detect/attribute 一致）**

```java
// VisionMindClient.java — 第 115 行
// 修改前:
body.put("image", base64);
// 修改后:
body.put("image_base64", base64);
```

注意：此修改需要与外部 Java API 的 `/v1/face/search` 接口同步。如果外部 API 尚未修改，需要先在外部 API 中兼容 `image_base64` 字段，或在此处保留 `image` 作为备用。

**方案**: 发送两个字段，向后兼容：

```java
// VisionMindClient.java — searchFaces 方法
body.put("image", base64);           // 兼容旧版
body.put("image_base64", base64);    // 统一命名
```

- [ ] **Step 2: 同样处理 registerFace — 添加 image_base64 字段**

```java
// VisionMindClient.java — registerFace 方法
body.put("image", base64);           // 兼容旧版
body.put("image_base64", base64);    // 统一命名
```

- [ ] **Step 3: 验证编译通过**

```bash
mvn compile -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor: unify API field naming, add image_base64 to search/register for consistency"
```

---

### Task 12: (P3) 清理冗余代码 — isRegisteredToLib 重复读取 + 已知设计限制说明

- [ ] **Step 1: 清理 FaceProcessingPipeline 末尾冗余读取**

```java
// FaceProcessingPipeline.java — 第 194-195 行
// 删除以下两行（fr 刚被保存后又读出来赋值回去，无实际效果）:
// fr.setIsRegisteredToLib("registered".equals(fr.getLibRegisterStatus()));
// fr.setCroppedImageUrl(fr.getCroppedImageUrl());
```

- [ ] **Step 2: 在 FaceProcessingPipeline 添加注释说明情绪分析使用全图而非裁剪区**

```java
// FaceProcessingPipeline.java — 在 processImage 的 emotion 分析部分添加注释
// 注: 这里传入全图给 VisionMindClient.analyzeAttribute()，REST API 内部会做检测+裁剪。
// 如需直接调用 gRPC EmotionService.Predict (需要 face crop)，需先抠图再传入。
```

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "chore: remove redundant field reassignments, document full-image vs crop design decision"
```

---

## 执行顺序与依赖关系

```
Phase 1 (P0)
  Task 1 (Redis Consumer)  ── 无前置依赖
  Task 2 (Async lifecycle)  ── 无前置依赖
        │
Phase 2 (P1)               需要 Phase 1 完成后验证管线可用
  Task 3 (Merge pipelines)  ── 依赖 Task 1（消费者用到了 orchestrator）
  Task 4 (heatmap filter)   ── 无前置依赖，可并行
  Task 5 (Emotion dims)     ── 无前置依赖，可并行
  Task 6 (Student fix)      ── 依赖 Task 3
        │
Phase 3 (P2)               可并行于 Phase 2
  Task 7 (BBox float)       ── 无前置依赖
  Task 8 (Disable V1)       ── 无前置依赖
  Task 9 (Tests)            ── 部分依赖 Task 7
        │
Phase 4 (P3)               无前置依赖
  Task 10 (JSON cleanup)    ── 无前置依赖
  Task 11 (Field naming)    ── 无前置依赖
```

**推荐并行策略:** Task 1 + Task 2 并行 → Task 3 + Task 4 + Task 5 + Task 7 + Task 8 + Task 10 + Task 11 并行 → Task 6 + Task 9 并行

---

## 验证清单

- [ ] Phase 1 完成后: `mvn compile` 无错误
- [ ] Phase 1 完成后: `mvn test` 无失败
- [ ] Phase 2 完成后: 验证 Redis stream consumer 启动日志 `ImageIngestConsumer subscribed`
- [ ] Phase 2 完成后: 验证 heatmap 返回过滤后的图片数
- [ ] Phase 2 完成后: 验证 EmotionRecord 的 7 个维度列有数据（SQL: `SELECT emotion_happy, emotion_sad FROM emotion_record LIMIT 1`）
- [ ] Phase 2 完成后: 验证 student 表无 `AUTO_%` 前缀的行
- [ ] Phase 3 完成后: 验证 BBox 支持 float（测试通过）
- [ ] Phase 3 完成后: 验证 FaceClusteringService V1 不再定时执行（日志无 `processPendingClusters` 调用）
- [ ] Phase 4 完成后: 全量测试通过
