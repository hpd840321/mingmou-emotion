# VisionMind 表情识别引擎 — 表情种类

## 概述

表情识别引擎基于 **EmotiEffLib + TensorRT**，独立部署于 `emotion_server`（端口 50057）。

## 支持表情

引擎可识别 **8 种**表情：

| ID | 中文 | 英文 | 说明 |
|----|------|------|------|
| 0 | 愤怒 | Anger | |
| 1 | 蔑视 | Contempt | |
| 2 | 厌恶 | Disgust | |
| 3 | 恐惧 | Fear | |
| 4 | 开心 | Happy | |
| 5 | 中性 | Neutral | 无明显情绪 |
| 6 | 伤心 | Sad | |
| 7 | 惊讶 | Surprise | |

## 代码来源

`cpp/emotion/emotion_service_impl.cpp`:
```cpp
const char* EmotionServiceImpl::EMOTION_LABELS[8] = {
    "愤怒", "蔑视", "厌恶", "恐惧", "开心", "中性", "伤心", "惊讶"
};
```

## 引擎参数

| 参数 | 值 |
|------|-----|
| 模型 | EmotiEffLib TRT |
| 输入 | 224×224×3 BGR 人脸裁剪 |
| 输出 | 8 维 softmax 概率 |
| 推理延迟 | ~10-30ms (GPU 0, RTX 2080Ti) |
| 内存占用 | ~75 MB |

## gRPC 接口

**服务:** `EmotionService` (端口 50057)

**请求:**
```protobuf
message EmotionRequest {
  bytes image_data = 1;  // JPEG 编码的人脸裁剪区
}
```

**响应:**
```json
{
  "success": true,
  "emotion": {
    "emotion": 4,
    "label": "开心",
    "probabilities": [0.01, 0.02, 0.03, 0.05, 0.75, 0.08, 0.04, 0.02]
  }
}
```

## 调用方式

内部调用链路（无需外部直接调用）：
```
Java API → face_server:50053/Analyze → 人脸裁剪 → JPEG 编码
                                       → gRPC emotion_server:50057/Predict
                                       → 返回 FaceEmotion 填入 FaceAnalysisResponse
```
