# 批量人脸处理架构设计

> 版本: v4  
> 日期: 2026-05-30  
> 状态: 待审核

---

## 一、问题陈述

### 1.1 当前缺陷

`FaceProcessingPipeline.processImage()` 存在两个关联的性能与功能缺陷:

**缺陷 A: 每张图片只处理单张人脸**

```java
// FaceProcessingPipeline.java:147-150
FaceDetectionResult.Face bestFace = faces.stream()
    .filter(f -> f.getConfidence() != null && f.getConfidence() >= confidenceThreshold)
    .max(java.util.Comparator.comparing(FaceDetectionResult.Face::getConfidence))
    .orElse(null);
```

教室照片通常包含 10-29 张人脸，但代码只取置信度最高的一张。其余人脸全部丢弃。这是目前 1400+ 张图片只产生 252 条 `face_record` 的根本原因。

**缺陷 B: 每裁剪一张脸就重新解码全图**

```java
// FaceCroppingService.java:33
BufferedImage img = ImageIO.read(originalImage.toFile());
```

`cropFace()` 接受 `Path` 参数，每次调用都从磁盘读取完整 JPEG 并解码为 `BufferedImage`（2560×1920 → ~15MB）。若处理 10 张人脸，全图被解码 10 次。

### 1.2 影响范围

| 影响 | 严重程度 |
|------|---------|
| 人脸数据覆盖率：应有 14000+ face_record，实有 252 | P0 功能缺失 |
| 管线吞吐量：每张图片重复解码 N 次 → 慢 N 倍 | P2 性能 |
| 磁盘 I/O：全图多次读取 → SSD 寿命 + 延迟 | P2 性能 |

---

## 二、目标约束

1. **零数据丢失**: 每张图片中所有置信度达标的人脸必须生成一条 `face_record`
2. **零冗余 I/O**: 每张图片的全图 JPEG 只读取和解码一次
3. **情绪尽力而为**: 通过 `/v1/face/attribute` 批量匹配情绪，匹配不上的人脸只存 `face_record` 不存 `emotion_record`
4. **DB schema 最小变更**: `class_image` 加 3 列（统计+标注路径），`face_record` 加 1 列（错误信息）
5. **向后兼容**: 已处理的图片不受影响，新管线可以无缝接管

---

## 三、架构设计

### 3.1 处理流水线

单张图片的处理流程分为 5 个阶段:

```
Phase 0: 资源加载 (一次性)
  ┌─────────────────────────────────────────────┐
  │  byte[] imageBytes = Files.readAllBytes()   │
  │  BufferedImage fullImage = ImageIO.read()   │
  │  (VisionMindClient 内部做 base64 编码)       │
  └─────────────────────────────────────────────┘
                       │
                       ▼
Phase 1: 人脸检测 (REST API)
  ┌─────────────────────────────────────────────┐
  │  POST /v1/face/detect                       │
  │  → 请求体含 tile_width=320 tile_height=320  │
  │  → List<Face> allFaces                      │
  │  → 过滤: confidence >= threshold            │
  └─────────────────────────────────────────────┘
                       │
                       ▼
Phase 2: 批量情绪 (REST API + 索引匹配)
  ┌─────────────────────────────────────────────┐
  │  POST /v1/face/attribute                    │
  │  → List<Attribute> allAttributes (tile=640) │
  │  → 索引匹配: attributes[i] ↔ faces[i]       │
  └─────────────────────────────────────────────┘
                       │
                       ▼
Phase 3: 生成标注图副本 (先于裁剪，避免像素共享冲突)
  ┌─────────────────────────────────────────────┐
  │  ⚠ Java getSubimage() 与原始 BufferedImage │
  │  共享 raster 数据。必须先复制再绘制。         │
  │                                              │
  │  annotatedCopy = fullImage (像素数据复制)     │
  │  Graphics2D 在 annotatedCopy 上绘制人脸框    │
  │  + 置信度文本                                │
  │  保存 data/annotated/<原路径>/attach_<文件>   │
  │  → annotatedImageUrl                         │
  └─────────────────────────────────────────────┘
                       │
                       ▼
Phase 4: 批量逐脸处理 (内存操作)
  ┌─────────────────────────────────────────────┐
  │  for each face in allFaces:                 │
  │    1. FaceRecord ← new (status=DETECTED)     │
  │    2. crop ← fullImage.getSubimage()        │
  │       (原始 fullImage 未被污染)              │
  │    3. 保存裁剪图到磁盘                       │
  │    4. 注册到 VisionMind 人脸库               │
  │    5. if matchedEmotion exists:             │
  │         EmotionRecord ← new                 │
  │         faceRecord.status = IDENTIFIED      │
  │    6. 加入 batch 列表                       │
  └─────────────────────────────────────────────┘
                       │
                       ▼
Phase 5: 记录统计 + 逐条持久化
  ┌─────────────────────────────────────────────┐
  │  classImage.faceDetectedCount = N           │
  │  classImage.emotionRecognizedCount = M      │
  │  classImage.annotatedImageUrl = path        │
  │                                              │
  │  for each FaceRecord in batch:              │
  │    try:                                     │
  │      faceRecordRepository.save(fr)           │
  │      if hasEmotion:                         │
  │        emotionRecordRepository.save(er)      │
  │    catch (Exception e):                     │
  │      fr.setErrorMessage(e.getMessage())     │
  │      faceRecordRepository.save(fr)           │
  │      continue (下一张脸)                     │
  │                                              │
  │  classImageRepository.save(classImage)       │
  └─────────────────────────────────────────────┘
  (逐条保存确保一条脸的失败不回滚其他脸)
```

### 3.2 标注图生成

**用途:**
- 教室场景复原：整张图片上标注人脸框位置，便于肉眼复核
- 个人历史记录：回溯某学生在某时间点的教室位置
- 聚类分析：可视化人脸空间分布，辅助调优检测参数

**生成策略:**
- 在裁剪**之前**对 `BufferedImage` 做像素级复制（`copyData(null)`），避免 `getSubimage()` 的 raster 共享冲突
- 在副本上使用 `Graphics2D` 绘制绿色矩形框 + 顶部置信度文本
- 保存为 JPEG，目录结构镜像原始数据:

```
data/annotated/官渡一中/初一班/2026-0521/课外活动-放学/
  attach_20260521164736_T08_0005AA30.jpg
```

**存储预估:** 每张标注图约 200-500KB（JPEG 压缩），1400 张约 500MB-1GB。

**失败处理:** 标注图生成失败不影响管线主流程（catch 后继续），仅 `annotated_image_url` 留空。

**目录创建:** 初始化时自动创建 `data/annotated/` 根目录（`Files.createDirectories()`），子目录在每次保存时按需创建。

**.gitignore:** `data/annotated/` 加入 `.gitignore`，与 `data/` 目录同级处理。

**性能:** Graphics2D 绘制在 BufferedImage 上进行，纯内存操作，每张图 < 50ms。

### 3.3 事务边界

`@Transactional` 标注在 `processImage()` 入口方法。拆分后的内部阶段方法在同一个事务内:

```
@Transactional
processImage():
  detectFaces()        ← 无 DB 操作(REST 调用), 事务不影响
  batchProcessFaces()  ← faceRecord/emotionRecord save() 在事务内
  markCompleted()      ← classImage save() 在事务内
```

逐条 `save()` 确保单张人脸失败不回滚整图（见 Phase 4）。

### 3.4 组件变更

| 组件 | 变更类型 | 说明 |
|------|---------|------|
| `FaceProcessingPipeline` | **重构** | `processImage()` 拆分为阶段方法；批处理循环；索引匹配；标注图生成 |
| `VisionMindClient` | **接口变更** | `detectFaces()` 新增 `tileWidth`/`tileHeight` 参数，发送到 `/v1/face/detect` |
| `FaceCroppingService` | **接口变更** | 新增 `cropFace(BufferedImage, ...)` 重载，保留旧 `cropFace(Path, ...)` 签名兼容性 |
| `FaceRecord` 实体 | **新增字段** | 增加 `errorMessage` 属性映射到 `error_message` 列 |
| `ClassImage` 实体 | **新增字段** | 增加 `faceDetectedCount`, `emotionRecognizedCount`, `annotatedImageUrl` |
| `FaceRecordRepository` | 无需变更 | `saveAll()` 由 Spring Data JPA 自动提供 |
| `EmotionRecordRepository` | 无需变更 | `saveAll()` 由 Spring Data JPA 自动提供 |

### 3.5 情绪与检测结果的索引匹配算法

**关键发现:** `/v1/face/attribute` 端点响应中每个 `attributes[i]` **不包含 `bbox`**（已实测验证），无法做基于位置的 IoU 匹配。

匹配策略改为**索引对齐**:

两个端点在内部分别调用 face_server 的检测引擎，按**置信度降序**返回结果。虽然 tile 参数不同（320 vs 640），但对于同一张人脸，检测引擎的内部排序逻辑一致:

```
detect 返回 (tile=320, 更敏感):
  faces[0]  ← 置信度最高的人脸 (大脸/中央)
  faces[1]
  faces[2]
  faces[3]
  faces[4]
  faces[5]  ← 后排小脸，640 检测不到
  
attribute 返回 (tile=640, 只覆盖较大人脸):
  attributes[0]  ← 与 faces[0] 是同一张脸（置信度最高）
  attributes[1]  ← 与 faces[1] 是同一张脸
  attributes[2]  ← 与 faces[2] 是同一张脸
  attributes[3]  ← 与 faces[3] 是同一张脸  ← 640 tile 最多检出 4 张
  (没有更多了)
```

**匹配规则:**
```
令 M = attributes.length（tile=640 能检测到的人脸数）
令 N = faces.length（tile=320 检测到的人脸数，M ≤ N）

对于 i ∈ [0, M):
  faces[i] ←→ attributes[i]  (索引对齐)

对于 i ∈ [M, N):
  faces[i] 无情绪数据（tile=640 未检测到）
```

**验证:** 首次部署时对前 50 张图片输出 bbox 对比日志:
```
INDEX MATCH CHECK: image=1001, face[0]={x=120,y=30,w=28,h=34},
  attr[0]={x=118,y=28,w=30,h=36}  ← 接近，索引匹配正确
```
50 张后关闭日志。若大量人脸出现 `dominantConfidence=null`（即 `M << N`），说明 tile=640 检测率过低，需排查 face_server 配置或回退到逐脸 `/v1/face/emotion` 方案。

### 3.6 EmotionRecord 数据源

`EmotionRecord` 通过索引匹配从 `/v1/face/attribute` 获取:

```
Phase 1 → List<Face> faces[] (N 张, tile=320)
Phase 2 → List<Attr> attributes[] (M 张, tile=640)

for i in [0, M):
  faces[i].emotion = attributes[i].emotion   // 索引 i 对齐
  faceRecord.dominantEmotion = attributes[i].emotion.label
  faceRecord.dominantConfidence = attributes[i].emotion.probability

for i in [M, N):
  faces[i].emotion = null   // tile=640 未检测到, 无情绪数据
```

`EmotionAnalysisResult.fromVmResponse()` 已支持从 `attributes[0].emotion` 解析格式（先前修复）。

`emotions` Map（per-dimension 概率）在 `/v1/face/attribute` 响应中不存在（该端点只返回 `label` + `probability`）。因此 `emotion_happy`, `emotion_sad` 等字段保留 null。如需全维度概率，可通过 `/v1/face/emotion` 逐脸补充（当前不做，Option B）。

---

## 四、性能分析

### 4.0 进度计数策略

进度计数器 `processedCount` 保持**图片级**计数，而非人脸级:

- 每张图片无论检测出多少人脸，处理完成后只 +1
- `onStatusChange(COMPLETED)` 和 `onStatusChange(FAILED)` 只在**整图**完成时触发
- 人脸级错误不触发进度更新（错误信息写入 `face_record.error_message`）
- 确保进度百分比始终 ≤ 100%

### 4.1 内存占用（单图片峰值）

| 对象 | 大小 | 阶段 | 说明 |
|------|------|------|------|
| `byte[] imageBytes` | ~1.4 MB | Phase 0→3 | 原始 JPEG |
| `BufferedImage` | ~15 MB | Phase 0→3 | 解码后的 ARGB 位图 |
| `List<FaceRecord>` | ~2 KB × N | Phase 3→4 | N 为人脸数 |
| 单个人脸裁剪 | ~10-50 KB | Phase 3 循环内 | 逐脸创建，循环迭代后 GC |
| **峰值** | **~17 MB** | Phase 3 | 管线串行处理，一次一图 |

### 4.2 管线吞吐量预估

| 步骤 | 当前 (1脸/图) | 改造后 (15脸/图) | 变化 |
|------|--------------|-----------------|------|
| 图片读取 | 1 次 | 1 次 | 不变 |
| JPEG 解码 | N 次 (N=人脸数) | 1 次 ✅ | 从 N→1 |
| REST detect | 1 次 | 1 次 | 不变 |
| REST attribute | 1 次 | 1 次 | 不变 |
| 人脸裁剪 | 1 次 (磁盘I/O) | N 次 (内存操作) ✅ | I/O 归零 |
| 人脸库注册 | 1 次 | N 次 | +N-1 次 |
| DB 写入 | 2 条 | 2N 条 | +2(N-1) 条 |

标注图生成: 在 `BufferedImage` 上用 `Graphics2D` 绘制 N 个矩形 + 保存 JPEG，约 20-50ms/图，可忽略不计。

**结论**: REST API 调用次数不变（2次/图），额外开销集中在内部操作（内存裁剪 + 人脸库注册 + DB 写入）。人脸库注册是主要的额外耗时，但这是必须的。

### 4.3 人脸库注册优化

`FaceRegistrationService.registerFaceToLibrary()` 调用 `POST /v1/facedb/register`。目前是逐脸注册。如果人脸库注册成为瓶颈，未来可改为异步（`@Async` + `CompletableFuture`），当前不做。

---

## 五、错误处理

### 5.1 错误记录策略

所有处理错误必须持久化到数据库，而不是仅输出日志。分两级存储:

**图片级错误**: 写入 `class_image.error_message` 字段，同时设置 `class_image.status = FAILED`。适用于图片读取失败、REST detect 失败等整图不可恢复的错误。

**人脸级错误**: 在 `face_record` 表新增 `error_message` 字段（`varchar(500)`）。当某张人脸的裁剪或注册失败时，错误信息写入该字段，`face_record` 仍然保存（`status = DETECTED`），不影响同图片的其他脸。

### 5.2 DB schema 变更

**class_image 表新增字段:**
```sql
ALTER TABLE class_image
  ADD COLUMN face_detected_count INT DEFAULT 0 COMMENT '该图片检测到的人脸数',
  ADD COLUMN emotion_recognized_count INT DEFAULT 0 COMMENT '该图片成功识别的情绪数',
  ADD COLUMN annotated_image_url VARCHAR(1000) DEFAULT NULL COMMENT '标注图路径（含人脸框）';
```

**face_record 表新增字段:**
```sql
ALTER TABLE face_record 
  ADD COLUMN error_message VARCHAR(500) DEFAULT NULL 
  COMMENT '处理失败原因，成功处理时为空';
```

### 5.3 错误处理矩阵

| 场景 | 记录位置 | 字段 | 对批次影响 |
|------|---------|------|-----------|
| 图片无法读取 | `class_image` | `error_message` + `status=FAILED` | 跳过该图所有处理 |
| REST detect 失败 | `class_image` | `error_message` + `status=FAILED` | 跳过该图所有处理 |
| 某张人脸裁剪失败 | `face_record` | `error_message` (新增) | 仅跳过该脸的注册，继续下一张 |
| 某张人脸注册失败 | `face_record` | `error_message` (新增) | 仅跳过该脸注册，face_record 仍保存 |
| REST attribute 失败 | 不记录 | — | 仅 emotion 数据丢失，人脸检测和裁剪照常 |

### 5.4 重复处理机制

错误记录数据库的核心目的是支持后续**精准重试**:

**图片级重试**: 已有 `POST /admin/pipeline/reset-failed`，将 `status=FAILED` 的 `class_image` 重置为 `PENDING`。管线下次运行时重新处理整图。

**人脸级重试（新增）**: 通过 `face_record.error_message IS NOT NULL` 查询可重试的失败人脸。重试策略：

```
方案 A: 管线内置重试（推荐）
  在 processAll() 中新增 Phase 0.5:
    查找所有 face_record.error_message IS NOT NULL 的记录
    按 class_image 分组
    对每个有失败人脸的图片:
      如果图片本身 COMPLETED → 仅重新处理失败的人脸
      如果图片 FAILED → 整图重处理

方案 B: 手动触发 API
  POST /admin/pipeline/retry-failed-faces
  查找所有 error_message 不为空的 face_record
  按 class_image 分组，重新裁剪 + 注册 + 情绪分析
```

**冗余处理保护**: 为避免重复处理已成功的人脸，重试时需跳过 `face_record.error_message IS NULL` 的记录。

**幂等性**: 
- `face_record` 的裁剪和注册操作是幂等的——重新裁剪会覆盖旧文件，重新注册会更新人脸库
- `EmotionRecord` 是 `face_record` 的一对一关系，重试时应先删除旧的 `emotion_record`（如果存在）
- 标注图: 重试时重新生成并覆盖 `annotated_image_url` 文件。若重试只涉及部分人脸（方案 A），需在标注图上更新对应人脸的矩形框（或整图重新生成，确保一致性）

### 5.5 前端查看

`PipelineMonitor.vue` 的实时日志已显示 `errorMessage`（来自 `class_image.error_message`）。人脸级错误需在 `FaceRecord` 详情页或 `StudentProfile` 中展示——当前不做，后续可通过 `face_record.error_message` 字段扩展。

### 5.6 原则

1. **单张人脸的处理失败不影响同图片的其他脸**
2. **单张图片的处理失败不影响管线批次**
3. **所有错误都有数据库记录，可追溯**
4. **错误信息包含具体原因**（如 `Cannot read image: /path/to/img.jpg`、`Empty crop region at x=0 y=0 w=0 h=0`）
5. **失败的人脸和图片都可以精准重试**，不重复处理已成功的部分

---

## 六、向后兼容

1. **DB schema**: `class_image` 新增 `face_detected_count`, `emotion_recognized_count`, `annotated_image_url`；`face_record` 新增 `error_message`。存量行新增字段均为 `NULL`/`0`，兼容
2. **API 响应**: `PipelineStatusController` 的 `/status` 和 `/data-dirs` 响应格式不变
3. **WebSocket 事件**: `onStatusChange` 事件格式不变
4. **已处理的图片**: 不会重复处理（`status=COMPLETED` 的图片被跳过）
5. **存量数据**: 已存在的 252 条 `face_record` 不受影响，`error_message` 为 NULL；存量 `class_image` 的 `face_detected_count` 和 `emotion_recognized_count` 为 0，可通过后续管线重新处理补充

---

## 七、工作量估算

| 任务 | 文件 | 预估 |
|------|------|------|
| `processImage()` 重构为阶段方法 | FaceProcessingPipeline.java | 2h |
| `VisionMindClient` 增加 tile 参数 | VisionMindClient.java | 0.3h |
| `cropFace(BufferedImage,...)` 重载 | FaceCroppingService.java | 0.5h |
| 索引匹配 + 批处理循环 | FaceProcessingPipeline.java | 1h |
| `FaceRecord` 实体增加 `errorMessage` 字段 | FaceRecord.java | 0.2h |
| `ClassImage` 实体增加 3 个统计字段 | ClassImage.java | 0.2h |
| 标注图生成 (Graphics2D 绘制人脸框) | FaceProcessingPipeline.java + 新方法 | 1h |
| DB migration (ALTER TABLE × 2) | V9 migration SQL | 0.3h |
| 人脸级错误记录 + 逐条持久化 (异常隔离) | FaceProcessingPipeline.java | 0.5h |
| 图片级进度计数 + 示意图 | PipelineProgressService.java | 0.2h |
| 失败人脸重试 API (retry-failed-faces) | PipelineStatusController.java | 0.5h |
| 索引匹配验证日志 (前50张) | FaceProcessingPipeline.java | 0.2h |
| 单元测试更新 | FaceProcessingPipelineTest.java | 1h |
| **合计** | | **~8h** |

---

## 八、未纳入范围

1. `/v1/face/emotion` 逐脸补充情绪 → 当前选择 Option B，不做
2. 人脸注册异步化 → 后续优化项
3. DB 分组查询终极优化 → 独立任务
4. 情绪标签排列顺序确认 → 由引擎工程确认
5. 标注图前端展示 → 当前仅存储，后续可在 `PipelineMonitor` 或 `StudentProfile` 中加图片查看功能
