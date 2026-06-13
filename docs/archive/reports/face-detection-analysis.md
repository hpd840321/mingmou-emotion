# 人脸检测 & 情绪识别 — 完整分析报告

> 生成时间: 2026-05-30

---

## 一、问题概述

### 1.1 现象

- 管线已处理 1282 张图片，但仅生成 **248 条 face_record**，**0 条 emotion_record**
- 当前人脸检测返回 0 张人脸
- 所有页面（校级大盘、学校组织、班级看板）无情绪数据可展示

### 1.2 影响范围

| 页面 | 依赖数据 | 当前状态 |
|------|---------|---------|
| 校级大盘 (SchoolOverview) | EmotionAggregation + face_record | ❌ 无数据 |
| 班级看板 (ClassDashboard) | EmotionRecord + face_record | ❌ 无数据 |
| 学校组织 (SchoolTree) | face_record + 学生关联 | ⚠️ 有 face_record 但无情绪 |
| 学生档案 (StudentProfile) | EmotionRecord | ❌ 无数据 |

---

## 二、数据现状

### 2.1 数据库统计

| 表 | 记录数 | 说明 |
|----|--------|------|
| `class_image` (COMPLETED) | 1282 | 已处理完成 |
| `class_image` (FAILED) | 751 | 失败（CircitBreaker 等原因） |
| `class_image` (PENDING) | 653 | 待处理 |
| `face_record` (DETECTED) | **248** | ✅ 早期运行检测到人脸 |
| `emotion_record` | **0** | ❌ 情绪分析从未成功 |
| `face_cluster` (pending) | 1298 | ✅ Qdrant 聚类已运行 |

### 2.2 关键发现

```
1282 COMPLETED 图片
  → 248 张检测到人脸 (face_record)
  → 0 张有情绪数据 (emotion_record)
  → 0 张在当前运行中检测到人脸
```

---

## 三、人脸检测 0 结果 — 根因分析

### 3.1 检测链路

```
FaceProcessingPipeline.processImage()
  → VisionMindClient.detectFaces()        REST POST /v1/face/detect
    → docker-api-1:8080                     VisionMind Java API
      → gRPC face_server:50053/Analyze      InspireFace C++ SDK
        → HFCreateInspireFaceSessionOptional(
            option, ALWAYS_DETECT, max_detect, detectPixelLevel, ...)
```

### 3.2 参数分析

| 参数 | 当前值 | 说明 |
|------|--------|------|
| `detectPixelLevel` | **160** → 已改为 **80** (测试后仍无效) | InspireFace SDK 参数，控制检测器输入分辨率级别，非最小人脸尺寸 |
| `max_detect` | 50 | 最大检测人脸数 |
| `HF_DETECT_MODE` | `ALWAYS_DETECT` | 图片模式，逐帧检测 |
| 图片尺寸 | 2560×1920 | 教室全景 |
| 人脸实际尺寸 | ~38×38 px (1.5% 图片宽度) | 教室后排学生 |
| 模型有效检测下限 | ~80px | InspireFace 模型架构限制 |

### 3.3 结论

**detectPixelLevel 160→80 修改后检测结果仍然为 0**。该参数不是最小人脸尺寸阈值，而是检测器内部处理分辨率。InspireFace 模型本身对 <80px 的人脸无法检测，这是模型架构限制，无法通过参数调整解决。

---

## 四、情绪分析 0 结果 — 根因分析

### 4.1 当前路径

```
FaceProcessingPipeline.processImage()
  → VisionMindClient.analyzeAttribute()      REST POST /v1/face/attribute
    → docker-api-1:8080                       返回: {attributes: [{gender, age, mask, quality, liveness}]}
                                                 ❌ 无 emotion/expression 字段
      → EmotionAnalysisResult.fromVmResponse() 查找 "emotion" 或 "expression"
                                                 ❌ 未找到 → dominantEmotion = null
        → EmotionRecord 创建条件:                emotionResult.getDominantEmotion() != null
                                                 ❌ 永不满足 → 0 条记录
```

### 4.2 可用路径

```diff
- REST /v1/face/attribute → {gender, age, mask}          ← 无情绪数据

+ gRPC face_server:50053/Analyze (with 0x80 flag)
+   → FaceEmotion {emotion, label, probabilities[8]}      ← 有情绪数据 ✅
+ 
+ gRPC emotion_server:50057/Predict
+   → EmotionResponse {emotion, label, probabilities}     ← 有情绪数据 ✅
```

### 4.3 端口可用性

| gRPC 服务 | 地址 | 状态 |
|-----------|------|------|
| face_server | `localhost:50053` | ✅ CONNECTED |
| emotion_server | `localhost:50057` | ✅ CONNECTED |
| attribute_server | `localhost:50058` | ✅ CONNECTED |

---

## 五、修复方案对比

### 5.1 人脸检测方案

| 方案 | 说明 | 工作量 | 效果预期 |
|------|------|--------|---------|
| **A: TileDetect 分块检测** | gRPC `TileDetect` 将大图切为小块分别检测再合并结果，专为大图小脸设计 | 中（修改 pipeline 调用方式） | 高 |
| **B: 图片放大后检测** | 图片 2x 放大（38px→76px）再送入检测器 | 低（VisionMindClient 加缩放） | 中 |
| **C: 切换检测模式** | `ALWAYS_DETECT` → `LIGHT_TRACK` 或 `TRACK_BY_DETECTION` | 低（改 face_server 代码） | 低（可能无效） |

### 5.2 情绪分析方案

| 方案 | 说明 | 工作量 | 效果预期 |
|------|------|--------|---------|
| **D: 改用 gRPC Analyze** | 将 `FaceProcessingPipeline` 中的 REST 调用替换为 `GrpcFaceServiceClient.analyze()` | 低（`GrpcFaceServiceClient` 已实现） | 高（直接获取 FaceEmotion） |
| **E: 单独调用 EmotionService** | 通过 gRPC 直接调 `emotion_server:50057/Predict` | 中（需新建 gRPC client） | 高 |

### 5.3 推荐方案

```
优先顺序:
  D (情绪 gRPC Analyze) —— 改动最小, GrpcFaceServiceClient 已就绪
  A (TileDetect 分块检测) —— 专为大图小脸场景设计
  B (图片放大) —— 临时兜底
```

---

## 六、相关文件

| 文件 | 路径 |
|------|------|
| face_server 源码 | `/app/face/src/face_service_impl.cpp` (Docker 容器内) |
| GrpcFaceServiceClient | `emotion-platform/src/main/java/.../GrpcFaceServiceClient.java` |
| FaceProcessingPipeline | `emotion-platform/src/main/java/.../FaceProcessingPipeline.java` |
| VisionMindClient | `emotion-platform/src/main/java/.../VisionMindClient.java` |
| InspireFace SDK | `/app/inspireface-sdk/include/inspireface.h` (Docker 容器内) |
