# /v1/face/attribute — 完整调用链分析

> 分析时间: 2026-05-30

---

## 一、调用链全景

```
emotion-platform:8090
  │
  │ VisionMindClient.analyzeAttribute()
  │   POST /v1/face/attribute
  │   body: {"image_base64": "...", "include": ["age","gender","expression","quality","liveness"]}
  │
  ▼
docker-api-1:8080
  │
  │ ExternalFaceController.attribute()
  │   ① 接收 AttributeRequest { image_base64, include }  ← include 字段声明但未使用
  │   ② features = 0xFFL  (硬编码，不使用 include)
  │   ③ faceLibraryService.analyze(imageData, imageData, features)
  │
  ▼
FaceLibraryService.analyze()
  │   ④ base64 解码 → ByteString
  │   ⑤ 构建 FaceAnalysisRequest { image_data, enabled_features }
  │   ⑥ faceStub().analyze(request)  → gRPC face_server:50053
  │
  ▼
face_server:50053/Analyze
  │   ⑦ InspireFace 人脸检测 (HFExecuteFaceTrack)
  │   ⑧ 对每张人脸裁剪区:
  │       → gRPC emotion_server:50057/Predict  (if features & 0x80)
  │       → gRPC attribute_server:50058/Predict (if features & 0x10)
  │   ⑨ 返回 FaceAnalysisResponse { faces[{token, attribute, emotion, quality, mask, liveness}] }
  │
  ▼
FaceLibraryService.analyze()  (响应映射)
  │   ⑩ getFacesCount() > 0 → 取 faces[0]
  │   ⑪ face.hasAttribute() → dto.setAge(), dto.setGender()
  │   ⑫ face.hasEmotion()   → dto.setExpression(EmotionResult{label, probability})
  │   ⑬ face.getMask(), getLiveness(), getQuality() → dto
  │   ⑭ 返回 FaceAnalysisResponse DTO
  │
  ▼
ExternalFaceController.attribute()  (JSON 序列化)
  │   ⑮ attr.put("age_bracket", dto.getAge())
  │   ⑯ attr.put("gender", "女"=0, "男"=1)
  │   ⑰ attr.put("mask", dto.getMask())
  │   ⑱ attr.put("quality", dto.getQualityScore())
  │   ⑲ attr.put("liveness", dto.getLivenessScore())
  │   ⑳ dto.getExpression() != null → attr.put("emotion", {label, probability})
  │   ㉑ 返回 { data: { attributes: [attr] } }
```

---

## 二、关于 `include` 参数

### 当前状态: **声明但未使用**

```java
// AttributeRequest DTO — 字段存在
@Data public static class AttributeRequest {
    public String image_base64;
    public List<String> include;    // ← 声明但从未读取
}

// attribute() 方法 — 忽略 include
public ResponseEntity<...> attribute(@RequestBody AttributeRequest req) {
    long features = 0xFFL;  // ← 硬编码全部功能
    FaceAnalysisResponse resp = faceLibraryService.analyze(
        req.image_base64, req.image_base64, features);
    // req.include 从未被引用
}
```

### `include` 参数无实际作用

无论传入什么 `include`，响应始终返回 **全部可用字段**: `age_bracket`, `age`, `gender`, `mask`, `quality`, `liveness`, `emotion`（如果检测到人脸）。

调用方（emotion-platform `VisionMindClient`）传入的 `include: ["age","gender","expression","quality","liveness"]` 完全被忽略。

---

## 三、请求/响应格式

### 请求

```json
{
  "image_base64": "<base64 编码的图片>"
}
```

`include` 字段可省略，不影响结果。

### 响应 (检测到人脸时)

```json
{
  "code": 0,
  "data": {
    "attributes": [{
      "age_bracket": 13,
      "age": 13,
      "gender": 1,
      "mask": false,
      "quality": 0.72,
      "liveness": 0.85,
      "emotion": {
        "label": "开心",
        "probability": 0.75
      }
    }]
  }
}
```

### 响应 (未检测到人脸时)

```json
{
  "code": 0,
  "data": {
    "attributes": [{
      "age_bracket": null,
      "age": null,
      "gender": null,
      "mask": null,
      "quality": null,
      "liveness": null
    }]
  }
}
```

**注意:** 无人脸时 `emotion` 字段不出现（`dto.getExpression() == null`）。

---

## 四、字段来源映射

| JSON key | Java DTO 字段 | 来源 | 类型 |
|----------|---------------|------|------|
| `age_bracket` | `FaceAnalysisResponse.age` | gRPC FaceAttribute.age_bracket | int |
| `age` | 同上 | 同上 | int |
| `gender` | `FaceAnalysisResponse.gender` | gRPC FaceAttribute.gender → "女"/"男" → 0/1 | int |
| `mask` | `FaceAnalysisResponse.mask` | gRPC FaceResult.mask | bool |
| `quality` | `FaceAnalysisResponse.qualityScore` | gRPC FaceResult.quality | float |
| `liveness` | `FaceAnalysisResponse.livenessScore` | gRPC FaceResult.liveness | float |
| `emotion.label` | `FaceAnalysisResponse.expression.label` | gRPC FaceEmotion.label (中文) | string |
| `emotion.probability` | `FaceAnalysisResponse.expression.probability` | gRPC FaceEmotion.probabilities (最大值) | float |

---

## 五、emotion-platform 兼容性

`EmotionAnalysisResult.fromVmResponse()` 解析路径:

```java
// Path 2: /v1/face/attribute 响应
vmData = { attributes: [{ age:13, gender:1, emotion:{label:"开心", probability:0.75} }] }
→ attrs = vmData.get("attributes")    // List<Map>
→ attrs.get(0)                        // { age:13, gender:1, emotion:{...} }
→ attrs.get(0).get("emotion")         // { label:"开心", probability:0.75 }  ✅ (已修复: 原为 "expression")
→ emotionData = { label:"开心", probability:0.75 }
```

---

## 六、features 位掩码

`analyze()` 使用 `features = 0xFFL` (255)，即全部功能:

| 位 | 值 | 功能 | 数据来源 |
|----|-----|------|---------|
| 0x01 | 1 | 人脸检测 | InspireFace SDK |
| 0x02 | 2 | 人脸识别(特征) | InspireFace SDK |
| 0x10 | 16 | 属性(性别/年龄) | InspireFace → attribute_server fallback |
| 0x20 | 32 | 质量评估 | InspireFace SDK |
| 0x40 | 64 | 人脸姿态 | InspireFace SDK |
| 0x80 | 128 | 情绪识别 | **emotion_server gRPC** (EmotiEffLib TRT) |

`0xFF = 0x01|0x02|0x10|0x20|0x40|0x80 = 255`
