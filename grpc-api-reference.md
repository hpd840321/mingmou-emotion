# VisionMind gRPC 接口文档

> 生成时间: 2026-05-29 | 版本: v1

---

## 一、架构总览

```
Java API (:8080)
  │
  ├── gRPC → face_server    (:50053) — 人脸检测/识别/比对/搜索
  ├── gRPC → emotion_server (:50057) — 情绪识别 (EmotiEffLib TRT)
  ├── gRPC → attribute_server (:50058) — 性别/年龄 (GenderAge TRT)
  └── gRPC → ocr_server     (:50052) — OCR (未部署)

内部 gRPC 通道（face_server 内部调用）:
  face_server → emotion_server  (:50057) — 人脸裁剪区 → 情绪标签
  face_server → attribute_server (:50058) — 人脸裁剪区 → 性别年龄
```

---

## 二、Proto 定义

**文件:** `visionmind-core/src/main/proto/inference.proto`  
**Package:** `visionmind.inference.v1`  
**Java Package:** `com.craftlabs.visionmind.core.grpc.proto`

---

## 三、服务清单

### 3.1 FaceService (端口 50053)

| RPC 方法 | 请求 | 响应 | 说明 |
|----------|------|------|------|
| `Predict` | InferenceRequest | InferenceResponse | 通用推理（管线使用） |
| `Analyze` | FaceAnalysisRequest | FaceAnalysisResponse | 多属性人脸分析 |
| `TileDetect` | TileDetectRequest | TileDetectResponse | 大图分块检测 |
| `CompareFaces` | FaceCompareRequest | FaceCompareResponse | 1:1 人脸比对 |
| `SearchFaces` | FaceSearchRequest | FaceSearchResponse | 1:N 人脸搜索 |
| `GetGpuMetrics` | GpuMetricsRequest | GpuMetricsResponse | GPU 指标 |

### 3.2 EmotionService (端口 50057)

| RPC 方法 | 请求 | 响应 | 说明 |
|----------|------|------|------|
| `Predict` | EmotionRequest | EmotionResponse | 情绪识别 (8类) |

### 3.3 AttributeService (端口 50058)

| RPC 方法 | 请求 | 响应 | 说明 |
|----------|------|------|------|
| `Predict` | AttributeRequest | AttributeResponse | 性别+年龄识别 |

---

## 四、消息定义

### 4.1 FaceService.Analyze — 多属性人脸分析

**请求:**
```protobuf
message FaceAnalysisRequest {
  string image_url = 1;           // 图片 URL 或 base64 (data:image/jpeg;base64,...)
  bytes image_data = 2;           // 原始图片字节 (与 image_url 二选一)
  int64 enabled_features = 3;     // 功能位掩码 (0=默认全部)
  map<string, string> params = 4; // 扩展参数
}
```

**功能位掩码 (enabled_features):**
```
0x01 = 人脸检测 (HF_ENABLE_FACE_DETECT)
0x02 = 人脸识别 (HF_ENABLE_FACE_RECOGNITION)  
0x04 = 活体检测 (HF_ENABLE_LIVENESS)
0x08 = 口罩检测 (HF_ENABLE_MASK_DETECT)
0x10 = 人脸属性 (HF_ENABLE_FACE_ATTRIBUTE) — 性别/年龄/种族
0x20 = 质量评估 (HF_ENABLE_QUALITY)
0x40 = 人脸姿态 (HF_ENABLE_FACE_POSE)
0x80 = 情绪识别 (HF_ENABLE_FACE_EMOTION) — 通过 EmotiEffLib
默认: 0x01|0x02|0x10|0x20|0x40 = 115 (0x73)
```

**响应:**
```protobuf
message FaceAnalysisResponse {
  bool success = 1;
  string error_message = 2;
  repeated FaceResult faces = 3;  // 检测到的所有人脸
}

message FaceResult {
  FaceBasicToken token = 1;       // 人脸框 + 置信度 + track_id
  FaceEulerAngle angle = 2;       // 欧拉角 (roll/yaw/pitch)
  FaceAttribute attribute = 3;    // 性别/年龄/种族
  FaceEmotion emotion = 4;        // 情绪标签 + 概率分布
  float quality = 5;              // 人脸质量分
  bool mask = 6;                  // 是否戴口罩
  float liveness = 7;             // 活体分数
  FaceInteraction interaction = 8; // 交互状态 (眨眼/张嘴)
  bytes feature = 9;              // 人脸特征向量 (512维 float32)
}
```

**JSON 示例响应:**
```json
{
  "success": true,
  "faces": [
    {
      "token": {"track_id": 1, "x": 1205.0, "y": 309.0, "width": 28.0, "height": 34.0, "confidence": 0.625},
      "attribute": {"race": 1, "gender": 0, "age_bracket": 16},
      "emotion": {"emotion": 4, "label": "开心", "probabilities": [0.01,0.02,0.03,0.05,0.75,0.08,0.04,0.02]},
      "quality": 0.85,
      "mask": false,
      "liveness": 0.92
    }
  ]
}
```

### 4.2 FaceService.TileDetect — 大图分块检测

**请求:**
```protobuf
message TileDetectRequest {
  string image_url = 1;             // 图片 URL
  bytes image_data = 2;             // 图片字节
  int32 tile_width = 3;             // 分块宽度 (默认640)
  int32 tile_height = 4;            // 分块高度 (默认640)
  float overlap_ratio = 5;          // 重叠比例 (默认0.2)
  float confidence_threshold = 6;   // 置信度阈值 (默认0.5)
  float nms_threshold = 7;          // NMS 阈值 (默认0.45)
  int64 enabled_features = 8;       // 功能位掩码
}
```

**响应:**
```protobuf
message TileDetectResponse {
  bool success = 1;
  string error_message = 2;
  repeated FaceResult faces = 3;   // 合并后的所有人脸
  TileInfo tile_info = 4;          // 分块信息
}

message TileInfo {
  int32 original_width = 1;
  int32 original_height = 2;
  int32 tile_width = 3;
  int32 tile_height = 4;
  float overlap_ratio = 5;
  int32 tiles_x = 6;          // X 方向分块数
  int32 tiles_y = 7;          // Y 方向分块数
  int32 total_tiles = 8;
}
```

### 4.3 FaceService.CompareFaces — 1:1 人脸比对

**请求:**
```protobuf
message FaceCompareRequest {
  string image_a = 1;    // 图片A URL
  string image_b = 2;    // 图片B URL
  float threshold = 3;   // 相似度阈值
}
```

**响应:**
```protobuf
message FaceCompareResponse {
  bool is_match = 1;      // 是否匹配
  float similarity = 2;   // 余弦相似度 (0~1)
  string error = 3;
}
```

### 4.4 FaceService.SearchFaces — 1:N 人脸搜索

**请求:**
```protobuf
message FaceSearchRequest {
  string image_url = 1;   // 查询图片 URL
  string library_id = 2;  // 人脸库 ID
  int32 top_k = 3;        // 返回 Top-K 结果
  float threshold = 4;    // 相似度阈值
}
```

**响应:**
```protobuf
message FaceSearchResponse {
  repeated FaceMatch matches = 1;
  string error = 2;
}

message FaceMatch {
  string face_id = 1;                    // 人脸 ID
  float similarity = 2;                  // 相似度
  map<string,string> metadata = 3;       // 元数据
}
```

### 4.5 EmotionService.Predict — 情绪识别

**请求:**
```protobuf
message EmotionRequest {
  bytes image_data = 1;  // JPEG 编码的人脸裁剪区
}
```

**响应:**
```protobuf
message EmotionResponse {
  bool success = 1;
  string error_message = 2;
  FaceEmotion emotion = 3;
}

message FaceEmotion {
  int32 emotion = 1;                    // 情绪标签 0-7
  repeated float probabilities = 2;     // 8 类 softmax 概率
  string label = 3;                     // 中文标签
}
```

**情绪标签映射:**
```
0 = 愤怒 (Anger)
1 = 蔑视 (Contempt)
2 = 厌恶 (Disgust)
3 = 恐惧 (Fear)
4 = 开心 (Happy)
5 = 中性 (Neutral)
6 = 伤心 (Sad)
7 = 惊讶 (Surprise)
```

**引擎信息:** 基于 EmotiEffLib + TensorRT，部署于 `emotion_server:50057`。
- 输入: 224×224×3 BGR 人脸裁剪区 (JPEG 编码)
- 输出: 8 维 softmax 概率 (索引 0-7 对应上方标签)
- 推理延迟: ~10-30ms (RTX 2080Ti)
- 显存占用: ~75 MB
- C++ 标签常量 (`emotion_service_impl.cpp`):
  ```cpp
  const char* EMOTION_LABELS[8] = {"愤怒", "蔑视", "厌恶", "恐惧", "开心", "中性", "伤心", "惊讶"};
  ```

**probabilities 数组索引说明:**
```protobuf
// probabilities 数组长度为 8，索引与 emotion 标签一一对应:
// probabilities[0] = Anger,   [1] = Contempt, [2] = Disgust, [3] = Fear
// probabilities[4] = Happy,    [5] = Neutral,  [6] = Sad,     [7] = Surprise
```

**调用链路:**
```
face_server:50053/Analyze → 人脸检测 → 人脸裁剪 → JPEG 编码
                         → gRPC emotion_server:50057/Predict
                         → 返回 FaceEmotion → 合并到 FaceAnalysisResponse
```

### 4.6 AttributeService.Predict — 性别年龄

**请求:**
```protobuf
message AttributeRequest {
  bytes image_data = 1;  // JPEG 编码的人脸裁剪区
}
```

**响应:**
```protobuf
message AttributeResponse {
  bool success = 1;
  string error_message = 2;
  int32 gender = 3;        // 0=女, 1=男
  int32 age = 4;           // 年龄
  string age_bracket = 5;  // "0-18" | "19-35" | "36-55" | "56+"
}
```

---

## 五、Java 调用方式

### 5.1 REST 调用模式 (VisionMindClient → Java API → gRPC)

实际运行中，pipeline 通过 REST 客户端 (`VisionMindClient`) 调用 VisionMind Java API，再由其内部转发 gRPC：

```java
// 1. 通过 VisionMindClient (REST) 调用外部 Java API
VisionMindClient client = ...;

// 人脸检测 (POST /v1/face/detect)
FaceDetectionResult result = client.detectFaces(imageBytes);

// 属性分析含表情 (POST /v1/face/attribute)
EmotionAnalysisResult emotion = client.analyzeAttribute(imageBytes);

// 1:N 人脸搜索 (POST /v1/face/search)
List<FaceSearchMatch> matches = client.searchFaces(imageBytes, 5, 0.5);

// 人脸注册 (POST /v1/facedb/register)
client.registerFace(id, name, extraJson, imageBytes);
```

> **注**: `GrpcFaceServiceClient` (直连 `FaceServiceGrpc`) 在代码中已定义但未在管线中使用。
> 若需直连 gRPC 绕过 Java API，可改用以下方式。

### 5.2 直连 gRPC Stub 模式 (GrpcFaceServiceClient)

```java
// 创建 Channel (或注入 GrpcFaceServiceClient)
ManagedChannel channel = ManagedChannelBuilder
    .forTarget("face-1:50053")
    .usePlaintext()
    .build();

// 人脸分析 (detect + attribute + emotion)
FaceServiceGrpc.FaceServiceBlockingStub faceStub = 
    FaceServiceGrpc.newBlockingStub(channel);

FaceAnalysisRequest req = FaceAnalysisRequest.newBuilder()
    .setImageData(ByteString.copyFrom(imageBytes))
    .setEnabledFeatures(0xFF)
    .build();

FaceAnalysisResponse resp = faceStub.withDeadlineAfter(60, TimeUnit.SECONDS).analyze(req);

// 大图分块检测 (TileDetect)
TileDetectRequest tileReq = TileDetectRequest.newBuilder()
    .setImageUrl(imageUrl)
    .setTileWidth(640)
    .setTileHeight(640)
    .setOverlapRatio(0.2f)
    .setConfidenceThreshold(0.5f)
    .setNmsThreshold(0.45f)
    .build();

TileDetectResponse tileResp = faceStub.tileDetect(tileReq);

// 特征位掩码常量 (对应 C++ HF_ENABLE_*)
long FEAT_DETECT      = 0x01;
long FEAT_RECOGNITION = 0x02;
long FEAT_LIVENESS    = 0x04;
long FEAT_MASK        = 0x08;
long FEAT_ATTRIBUTE   = 0x10;
long FEAT_QUALITY     = 0x20;
long FEAT_POSE        = 0x40;
long FEAT_EMOTION     = 0x80;

// 情绪识别 (单独 EmotionService)
EmotionServiceGrpc.EmotionServiceBlockingStub emoStub =
    EmotionServiceGrpc.newBlockingStub(
        ManagedChannelBuilder.forTarget("emotion:50057").usePlaintext().build());

EmotionResponse emoResp = emoStub.predict(
    EmotionRequest.newBuilder().setImageData(jpegBytes).build());
```

### 5.3 REST API 调用 (无需 gRPC 客户端)

```bash
# 人脸检测
curl -X POST http://localhost:8080/api/v1/face/detect \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -d '{
    "imageUrl": "data:image/jpeg;base64,...",
    "tileWidth": 640,
    "tileHeight": 640,
    "overlapRatio": 0.2,
    "confidenceThreshold": 0.5,
    "nmsThreshold": 0.4
  }'

# 人脸分析
curl -X POST http://localhost:8080/api/v1/face/analyze \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -d '{"imageUrl": "data:image/jpeg;base64,...", "enabledFeatures": 255}'

# 外部 API 人脸检测 (POST /v1/face/detect)
# 请求字段: image_base64 (base64 编码的图片)
curl -X POST http://localhost:8080/v1/face/detect \
  -H "Content-Type: application/json" \
  -d '{"image_base64": "..."}'

# 外部 API 属性分析含表情 (POST /v1/face/attribute)
# 请求字段: image_base64, include (可选分析项列表)
curl -X POST http://localhost:8080/v1/face/attribute \
  -H "Content-Type: application/json" \
  -d '{"image_base64": "...", "include": ["age","gender","expression","quality","liveness"]}'

# 外部 API 人脸搜索 (POST /v1/face/search)
# 请求字段: image (base64), top_k, threshold
curl -X POST http://localhost:8080/v1/face/search \
  -H "Content-Type: application/json" \
  -d '{"image": "...", "top_k": 5, "threshold": 0.5}'

# 外部 API 人脸库注册 (POST /v1/facedb/register)
# 请求字段: id, name, extra, image (base64 或 data URI)
curl -X POST http://localhost:8080/v1/facedb/register \
  -H "Content-Type: application/json" \
  -d '{"id": "face_1_2_3", "name": "face_1_2_3", "extra": "{\"student_id\":42}", "image": "data:image/jpeg;base64,..."}'

# ⚠️ 注意: search 和 register 接口使用字段名 image,
#   而 detect/attribute 使用 image_base64, 命名不统一。
```

---

## 六、服务地址

| 服务 | 容器内地址 | 宿主机端口 | 说明 |
|------|-----------|-----------|------|
| face_server | face-1:50053 | 50053 | 人脸检测/识别/比对/搜索 |
| emotion_server | emotion:50057 | 50057 | 情绪识别 |
| attribute_server | attribute:50058 | 50058 | 性别年龄识别 |
| Java API | api:8080 | 8080 | REST 网关 |
| Qdrant | qdrant:6333 | 6333 | 向量搜索 |

---

## 七、特征位掩码参考

```java
// 常用组合
long DETECT_ONLY       = 0x01;          // 仅检测人脸框
long DETECT_RECOGNIZE  = 0x01 | 0x02;   // 检测 + 特征提取
long FULL_ANALYSIS     = 0xFF;          // 全部功能
long NO_EMOTION        = 0xFF & ~0x80;  // 全部除了情绪 (InspireFace 内置)
long ATTRIBUTES_ONLY   = 0x01 | 0x10;   // 检测 + 属性
```

---

## 八、视频流 gRPC (已移除)

`VideoProcessor` 服务和 `video_processor.proto` 中的接口已从当前部署中移除。
