# 人脸检测精度复盘 — emotion-platform 工作区

## 检测管线架构

```
[Redis Stream] → ImageProcessingOrchestrator.pollPendingImages() (每5s)
                 → processImage(classImage)
                     → VisionMindClient.detectFaces(imageBytes)
                         → POST /v1/face/detect (返回bbox + confidence)
                     → VisionMindClient.analyzeAttribute(imageBytes)
                         → POST /v1/face/attribute (返回emotion/age/gender)
                 → ImageProcessingPersistenceService.saveResults()
                     → face_record + emotion_record
```

---

## 1. ❌ 核心问题：无置信度阈值过滤

**文件**: `ImageProcessingOrchestrator.java` 第110-132行

```java
faceResult = faceService.detectFaces(imageBytes);

// 只判断列表是否为空，不做任何置信度过滤
if (faceResult.getFaces() == null || faceResult.getFaces().isEmpty()) {
    persistenceService.markCompleted(classImage.getId());
    return;
}

// 直接取第一个人脸，不检查 confidence
persistenceService.saveResults(classImage.getId(),
    faceResult.getFaces().get(0),        // ← 无论置信度多低
    emotionResult,
    faceResult.getFaces().size());
```

**影响**:
- VisionMind返回的人脸置信度可低至 0.51 (实际测试值)
- 即使置信度 0.1 的误检也会被当成有效人脸进行情绪分析
- 远距离/侧脸的模糊检测结果直接进入下游

**建议**:
```java
float CONFIDENCE_THRESHOLD = 0.5f;  // 可配置
List<Face> validFaces = faceResult.getFaces().stream()
    .filter(f -> f.getConfidence() >= CONFIDENCE_THRESHOLD)
    .collect(Collectors.toList());
if (validFaces.isEmpty()) {
    log.warn("No valid faces after confidence filter (threshold={})", CONFIDENCE_THRESHOLD);
    persistenceService.markCompleted(classImage.getId());
    return;
}
```

---

## 2. ❌ 情绪分析失败默认 "neutral" 掩盖问题

**文件**: `EmotionAnalysisResult.java` 第36-39行

```java
public static EmotionAnalysisResult fromVmResponse(Map<String, Object> vmData) {
    // ... mapping logic ...
    if (result.getDominantEmotion() == null) {
        result.setDominantEmotion("neutral");   // ← 默认neutral
        result.setDominantConfidence(0.5f);     // ← 默认50%置信度
    }
    return result;
}
```

**影响**:
- VisionMind API 报错或返回空时，硬编码 fallback 为 "neutral" / 0.5
- 下游 `ImageProcessingPersistenceService.saveResults()` 将此误判为有效识别, 将 `FaceStatus` 设为 `IDENTIFIED` (第90行) 而非 `UNIDENTIFIED`
- 无法区分"真实neutral"和"分析失败"

**建议**: 当 VisionMind 返回 null/异常时，将 `emotionResult` 保持 null 而非 fallback。让 `saveResults` 在 emotionResult==null 时设置 `FaceStatus.UNIDENTIFIED` 且不创建 EmotionRecord。

---

## 3. ❌ 人脸注册传空图片数据

**文件**: `FaceLibraryService.java` 第73-76行

```java
@Transactional
public void annotateCluster(Long clusterId, AnnotateRequest request) {
    // ... save student ...
    visionMind.registerFace(request.getStudentNo(), request.getStudentName(),
        extraJson, new byte[0]);  // ← 空字节数组!
    // ...
}
```

registerFace 调用 VisionMind 的 `/v1/facedb/register` 接口时传入 `new byte[0]` 作为图片。这将导致 VisionMind 的人脸库注册失败（上传空数据到 SeaweedFS 后再 gRPC 调用 face_server 提取特征会返回错误）。

---

## 4. ⚠️ 人脸聚类使用粗哈希而非特征向量比对

**文件**: `FaceClusteringService.java` 第101-103行

```java
private String extractPrefix(String faceToken) {
    return faceToken.length() >= 8 ? faceToken.substring(0, 8) : faceToken;
}
```

**问题**: 聚类依据是 face token 的前8个字符，而非实际的特征向量余弦相似度。这不是真正的人脸聚类：
- 不同学生的人脸可能因 token 前缀相同而被归入同一组
- 同一学生的不同人脸可能因 token 前缀不同被分到不同组
- 聚类结果不可靠，后续的人工标注也会被误导

---

## 5. ⚠️ EmotionRecord 仅存 dominant 值，各维度概率为空

**文件**: `ImageProcessingPersistenceService.java` 第83-91行

```java
if (emotionResult != null && emotionResult.getDominantEmotion() != null) {
    EmotionRecord emotionRecord = new EmotionRecord();
    emotionRecord.setFaceRecord(faceRecord);
    emotionRecord.setDominantEmotion(emotionResult.getDominantEmotion());
    emotionRecord.setDominantConfidence(emotionResult.getDominantConfidence());
    // ❌ emotionHappy, emotionSad, etc. 全为 null
    emotionRecordRepository.save(emotionRecord);
```

`EmotionRecord` 实体定义了各情绪维度字段 (`emotionHappy`, `emotionSad`, `emotionNeutral` 等6个独立维度)，但 `VisionMindClient.analyzeAttribute()` 返回的 `EmotionAnalysisResult` 中只有 `dominantEmotion` 和 `dominantConfidence`，**独立的情绪分布概率被丢弃**。

---

## 6. ⚠️ 人脸质量分数未从 VisionMind 获取

`VisionMindClient.detectFaces()` 调用的 `/v1/face/detect` 外部端点仅返回 `bbox + confidence`，不包含 `quality`。即使 C++ 引擎计算了质量分，也无法被 emotion-platform 获取用于进一步过滤。

---

## 修复优先级

| # | 问题 | 严重度 | 文件 | 工作量 |
|---|------|--------|------|--------|
| 1 | 置信度阈值过滤缺失 | **严重** | `ImageProcessingOrchestrator.java` | 小 |
| 2 | 情绪失败默认 neutral | **高** | `EmotionAnalysisResult.java` + `saveResults` | 小 |
| 3 | 人脸注册传空图片 | **高** | `FaceLibraryService.java` | 中 |
| 4 | 人脸聚类粗哈希 | 中 | `FaceClusteringService.java` | 大 (需引入特征向量) |
| 5 | 情绪维度未存储 | 低 | `VisionMindClient` + `PersistenceService` | 中 |
| 6 | 质量分未获取 | 低 | `VisionMindClient` (依赖引擎侧修复) | 小 |
