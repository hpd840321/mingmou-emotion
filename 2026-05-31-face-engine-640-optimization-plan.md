# 人脸引擎 640 模型切换优化方案

**日期**: 2026-05-31
**范围**: VisionMind C++ face_server + emotion-platform Java pipeline

---

## 一、问题背景

### 1.1 当前状态

教室摄像头采集 2560×1920 图片，人脸约 38×38 像素。当前 `detectPixelLevel=160` 导致：

```
教室原图 2560×1920（人脸 38px）
  → detectPixelLevel=160 → 压缩至 213×160 → 人脸变为 3.2×3.2px
  → SCRFD-160 模型无法检测 <10px 人脸
  → 0 张人脸返回
  → 全线数据断裂
```

### 1.2 核心根因

`cpp/face/src/face_service_impl.cpp:187`:
```cpp
HFCreateInspireFaceSessionOptional(option, HF_DETECT_MODE_ALWAYS_DETECT,
    max_detect, 160, -1, session);
//           detectPixelLevel = 160  ← 写死为最低档
```

InspireFace SDK API 文档（`inspireface.h:556`）：
> `detectPixelLevel`: **the larger the better**, need to input a multiple of 160, such as **160, 320, 640**, default value **-1 is 320**.

当前代码比默认值还低。

---

## 二、两种检测路径的影响分析

### 2.1 Analyze 路径（`POST /v1/face/attribute`）

| detectPixelLevel | 有效分辨率 | 38px 人脸变为 | 检测能力 |
|:----------------:|:----------:|:-------------:|:--------:|
| 160 | 213×160 | 3.2×3.2px | ❌ 不可检测 |
| 320 | 427×320 | 6.3×6.3px | ⚠️ 勉强 |
| **640** | **853×640** | **12.7×12.7px** | **✅ 可靠** |

**影响程度：★★★★★** 显著——detectPixelLevel 直接决定小脸信息留存率。

### 2.2 TileDetect 路径（`POST /v1/face/detect`）

TileDetect 将大图切成小块分别处理。每块 tile 本身 ≤640px，detectPixelLevel=640 不会进一步提升单块分辨率。

真正影响 TileDetect 的是 **tile 尺寸**：

| tile 尺寸 | tiles 数 | 单块推理 | 总时间 | 小脸完整度 |
|:--------:|:--------:|:--------:|:------:|:----------:|
| 640×640 | 20 | ~60ms | ~1.2s | 完整 ✅ |
| 480×480 | 36 | ~35ms | ~1.3s | 较完整 ✅ |
| 320×320 | 80 | ~15ms | ~1.2s | 可能被切 ⚠️ |

**影响程度：★☆☆☆☆** 基本无关——tile 尺寸才是决定因素。

---

## 三、优化方案

### 3.1 C++ face_server 引擎（必须项）

#### 3.1.1 P0：切换 detectPixelLevel

```cpp
// face_service_impl.cpp createSession() 第 187 行
// 改前:
return HFCreateInspireFaceSessionOptional(
    option, HF_DETECT_MODE_ALWAYS_DETECT, max_detect, 160, -1, session);
//                                                     ↑↑↑

// 改后:
return HFCreateInspireFaceSessionOptional(
    option, HF_DETECT_MODE_ALWAYS_DETECT, max_detect, 640, -1, session);
//                                                     ↑↑↑
```

**影响**：SCRFD-640 模型自动加载，全图检测精度提升，GPU 显存增量约 100MB。

#### 3.1.2 P1：session 参数后置调优

```cpp
// 在 acquireSession() 返回 HFExecuteFaceTrack 之前调用
HFSessionSetTrackPreviewSize(session, 640);
HFSessionSetFilterMinimumFacePixelSize(session, 0);   // 不过滤小脸
HFSessionSetFaceDetectThreshold(session, 0.3f);        // 降低检测阈值
```

这些参数当前未设置，使用默认值。显式调优确保不丢失小脸。

#### 3.1.3 P2：清理 emotion 路径冲突

当前 `Analyze()` 中当 `features & 0x80` 时，InspireFace 内置 emotion 和外部 EmotiEffLib gRPC 都会执行，存在冲突（见代码注释）。应统一走外部 EmotiEffLib。

### 3.2 Java API 层（优化项）

#### 3.2.1 P1：调整 TileDetect 默认 tile 尺寸

```java
// ExternalFaceController.java:65-66
// 改前:
int tw = req.tile_width != null ? req.tile_width : 640;
int th = req.tile_height != null ? req.tile_height : 640;
// 改后:
int tw = req.tile_width != null ? req.tile_width : 480;
int th = req.tile_height != null ? req.tile_height : 480;
```

480×480 在 tiles 数量和单块推理速度间取得平衡。

#### 3.2.2 P1：增大 RestTemplate 超时

```java
// VisionMindClient.java（外部项目）
// RestTemplate 连接/读取超时从默认 → 30s
// 以匹配 640 模型下单图 Analyze ~250ms 的新耗时代数
```

#### 3.2.3 P2：放宽熔断器阈值

```yaml
# application.yml（外部项目）
resilience4j.circuitbreaker:
  configs:
    visionmind:
      failure-rate-threshold: 80    # 80% 失败才熔断
      minimum-number-of-calls: 100  # 100 次调用窗口
      wait-duration-in-open-state: 10s  # 10s 后恢复
```

---

## 四、性能预期

### 4.1 单图推理耗时对比

| 操作 | 当前 (160) | 改后 (640) | 变化 |
|------|:---------:|:---------:|:----:|
| Analyze 全图推理 | ~15ms | ~250ms | +16× |
| TileDetect (480 tiles) | ~700ms | ~1.3s | +1.9× |
| 小脸检测率 | 0% | ~60-80% | 核心收益 |

### 4.2 7253 张 classroom 图预期产出

| 指标 | 当前 | 改后预期 |
|:----|:----:|:--------:|
| 检测到人脸的图片 | 0 | ~4,000-5,800 |
| FaceRecord 生成 | 0 | ~20,000-60,000 |
| EmotionRecord | 0 | ~20,000-60,000 |
| 全管线耗时 | N/A（无数据） | ~20-40分钟 |

### 4.3 GPU 资源

| 资源 | 当前 | 改后 |
|:----|:----:|:----:|
| face_server GPU 显存 | ~15 MiB | ~200-300 MiB（含模型常驻） |
| GPU 利用率 | 0% | 推理时 ~30-50% |

---

## 五、实施步骤（推荐顺序）

```
Step 1: face_service_impl.cpp 改 detectPixelLevel=640（P0）
  → 重新构建 face Docker 镜像
  → 重启 face-1 容器
  → 用裁剪人脸测试 Analyze 路径

Step 2: 新增 session 参数调优（P1）
  → SetTrackPreviewSize, SetFilterMinimumFacePixelSize, SetFaceDetectThreshold
  → 重新构建 face Docker 镜像
  → 用教室全景图测试

Step 3: 调整 TileDetect 默认 tile（P1）
  → ExternalFaceController.java 480×480 默认值
  → 重启 api 容器

Step 4: emotion-platform 超时+熔断（P1-P2）
  → RestTemplate timeout 30s
  → Circuit breaker threshold 放宽

Step 5: 执行全量 pipeline
  → reset failed→pending
  → run pipeline
  → 验证 emotion 数据产出
```

---

## 六、回退方案

如切换到 640 后遇到性能问题不可接受：

1. **降级到 320**：将 `detectPixelLevel` 设为 320（比 160 好但比 640 快 4 倍）
2. **按需切换**：将 `detectPixelLevel` 做成 gRPC 参数传递，高精度场景用 640，低延迟场景用 320
3. **TileDetect 兜底**：Analyze 路径超时时自动回退到 TileDetect

---

---

## 七、emotion-platform pipeline 改造

通过审查 `/home/zebra/Downloads/官渡一中初一班-0526/emotion-platform/` 全部代码，
发现存在 **两条并行处理路径**，以及多个需要配合 640 模型改造的点。

### 7.1 两条处理路径架构

```
┌─────────────────────────────────────────────────────────────────────┐
│ 路径 A: ImageProcessingOrchestrator（旧，并发）                     │
│                                                             │
│  ThreadPool (6线程, Batch=20, 180s超时)                           │
│    → FaceRecognitionClient.detectFaces()  → /v1/face/detect       │
│    → EmotionRecognitionClient.analyzeEmotion() → /v1/face/attribute │
│    → ImageProcessingPersistenceService.saveResults()               │
│    → FaceRecord (student=null) + EmotionRecord                     │
│    → 不裁剪、不注册人脸库                                             │
│                                                                     │
│ 路径 B: FaceProcessingPipeline（新，顺序，当前默认）                   │
│                                                                     │
│  PipelineStatusController.runPipeline()                             │
│    → async pipelineExecutor (4-6线程)                               │
│    → FaceProcessingPipeline.processAll() → processImage()           │
│    → visionMindClient.detectFaces() → /v1/face/detect              │
│    → 裁剪人脸 → FaceCroppingService                                 │
│    → 注册人脸库 → FaceRegistrationService → /v1/facedb/register    │
│    → visionMindClient.analyzeAttribute() → /v1/face/attribute      │
│    → FaceRecord + EmotionRecord + status更新                         │
└─────────────────────────────────────────────────────────────────────┘
```

**当前默认触发方式**：`POST /api/v1/admin/pipeline/run` → `PipelineStatusController` → 异步执行路径 B。

### 7.2 各文件需要修改的具体内容

#### 7.2.1 RestTemplate 超时

**文件**: `config/RestTemplateConfig.java`

```java
// 改前:
@Bean
public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder.build();  // 默认超时，可能 5s 或无限
}

// 改后:
@Bean
public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
        .setConnectTimeout(Duration.ofSeconds(10))
        .setReadTimeout(Duration.ofSeconds(120))  // 640 模型 Analyze 需要更长时间
        .build();
}
```

**原因**：640 模型下单图 Analyze 耗时从 ~15ms 增至 ~250ms，批处理时 RestTemplate 可能超时。

#### 7.2.2 VisionMindClient 熔断配置

**文件**: `resources/application.yml`

```yaml
resilience4j:
  retry:
    configs:
      default:
        max-attempts: 3
        wait-duration: 2s          # 从 1s 改为 2s，给 640 模型更长响应时间
        exponential-backoff-multiplier: 2
  
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 50
        failure-rate-threshold: 80          # 已合理，保持
        wait-duration-in-open-state: 10s    # 从 30s 改为 10s，加快恢复
        permitted-number-of-calls-in-half-open-state: 5
```

**当前配置已近合理**：`failure-rate-threshold=80` 和 `sliding-window-size=50`
已在上次修复中调整过。只需微调 retry wait-duration 和 open-state 恢复时间。

#### 7.2.3 FaceProcessingPipeline 处理多个人脸

**文件**: `service/FaceProcessingPipeline.java`

当前代码只选取置信度最高的一张人脸：

```java
FaceDetectionResult.Face bestFace = faces.stream()
    .filter(f -> f.getConfidence() != null && f.getConfidence() >= confidenceThreshold)
    .max(java.util.Comparator.comparing(FaceDetectionResult.Face::getConfidence))
    .orElse(null);
```

**建议改为保留全部符合阈值的人脸**：

```java
// 改后：保留全部高置信度人脸
List<FaceDetectionResult.Face> validFaces = faces.stream()
    .filter(f -> f.getConfidence() != null && f.getConfidence() >= confidenceThreshold)
    .collect(Collectors.toList());

for (FaceDetectionResult.Face face : validFaces) {
    // 为每张脸创建 FaceRecord + 裁剪 + 注册 + 情绪分析
}
```

**原因**：640 模型能检测到教室多个人脸，只取 1 个会丢失大部分数据。

#### 7.2.4 ImageProcessingPersistenceService 也需适配多脸

**文件**: `service/ImageProcessingPersistenceService.java`

当前 `saveResults()` 只保存单个人脸。如需保留多脸，改为接受 List：

```java
@Transactional
public void saveResults(Long imageId, List<FaceDetectionResult.Face> faces,
                         EmotionAnalysisResult emotionResult, int totalFaces) {
    // 为每张脸创建 FaceRecord + EmotionRecord
}
```

#### 7.2.5 FaceProcessingPipeline 效率优化

**文件**: `service/FaceProcessingPipeline.java`

当前每个图片顺序处理，对于 7253 张图，即使每张 250ms 也需要约 30 分钟。
**建议**：利用 `pipelineExecutor` 的 4-6 线程池，改为并发处理：

```java
// 在 processAll() 中改用并发处理
ExecutorService executor = Executors.newFixedThreadPool(6);
List<CompletableFuture<Void>> futures = pending.stream()
    .map(ci -> CompletableFuture.runAsync(() -> processImage(ci), executor))
    .collect(Collectors.toList());
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

但需注意：640 模型 GPU 推理是串行的（单 GPU 单模型），6 线程并发只是增加了排队等待，不会提升 GPU 吞吐量。建议保持顺序处理，但 **增加 60s RestTemplate 超时以防万一**。

#### 7.2.6 FaceDetectionResult - 无改动需求

**文件**: `model/dto/FaceDetectionResult.java`

已支持多个人脸解析，无需修改。

#### 7.2.7 EmotionAnalysisResult - 无改动需求

**文件**: `model/dto/EmotionAnalysisResult.java`

已兼容 `/v1/face/attribute` 和 `/v1/face/emotion` 两种返回格式，无需修改。

### 7.3 数据链路完整性分析

```
640 模型检测到人脸后，完整数据链路：

拍照 → class_image (PENDING)
  → FaceProcessingPipeline.processImage()
  → detectFaces() → 返回 N 张人脸 ✅（640 模型）
  → 每张人脸：
      → FaceRecord (student=null, status=DETECTED)
      → FaceCroppingService.cropFace() → 保存裁剪图
      → FaceRegistrationService.registerFaceToLibrary() → Qdrant 向量库
      → analyzeAttribute() → EmotionRecord
      → FaceRecord.status = IDENTIFIED

  ↓（定时任务）
  
FaceClusteringServiceV2.scheduledClustering() (每 1 小时)
  → 从 Qdrant 滚动全部向量
  → 两两计算余弦相似度 (阈值 0.7)
  → 合并相似人脸为 FaceCluster
  → autoAnnotateClusters() → 创建 Student + 回填 face_record.student_id

  ↓

EmotionAggregationService.aggregate()
  → 按 student_id + date + period_id 聚合
  → 生成 EmotionAggregation
  → 仪表盘有数据 ✅
```

**完整链路的关键后置条件**：
| 步骤 | 必要条件 | 当前状态 |
|:----|---------|:--------:|
| 人脸检测 | detectPixelLevel=640 | ❌→✅ 改后 |
| FaceRecord 生成 | 检测到人脸 | ❌→✅ 改后 |
| 裁剪 + 注册库 | FaceRecord 存在 | ✅ 已有 |
| Qdrant 向量存储 | 注册成功 | ✅ 已有 |
| 聚类 + 学生关联 | Qdrant 有向量 | ⚠️ 需等 1h 定时 |
| 情绪聚合 | 学生已关联 | ⚠️ 聚类完成后触发 |

### 7.4 Student 表为空的问题

当前 `Student` 表 0 条记录。聚类服务 `autoAnnotateClusters()` 会自动创建 `auto_{classId}_{clusterId}` 格式的 student。
这意味着：
- **第一次运行聚类**：生成虚拟学生（auto_1_1, auto_1_2, ...）
- **人脸与学生关联**：face_record.student_id 被回填
- **聚合开始工作**：EmotionAggregationService.aggregate() 产生数据

如果需要**真实学生姓名映射**，需要额外的标注流程。

### 7.5 pipeline 修改清单汇总

| # | 文件 | 改动内容 | 必要程度 | 工作量 |
|:-:|:----|---------|:--------:|:------:|
| 1 | `config/RestTemplateConfig.java` | 设置 connectTimeout=10s, readTimeout=120s | **P1 必须** | 小 |
| 2 | `resources/application.yml` | retry wait-duration 1s→2s, CB open-state 30s→10s | P1 建议 | 小 |
| 3 | `service/FaceProcessingPipeline.java` | 多脸处理（保留全部而非1个） | **P1 必须** | 中 |
| 4 | `service/ImageProcessingPersistenceService.java` | saveResults 适配多脸 | P1 建议 | 小 |
| 5 | `service/FaceProcessingPipeline.java` | 并发处理（可选，GPU 瓶颈下收益有限） | P2 可选 | 中 |

---

*方案由 Sisyphus 自动生成 · 2026-05-31 · 含完整 emotion-platform 代码审查*
