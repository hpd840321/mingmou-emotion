# 人脸检测 & 情绪识别 — REST API 完整调用方案

> 版本: 2026-05-30 最终版

---

## 一、架构

```
Emotion Platform :8090
  │ REST
  ▼
VisionMind Java API :8080
  ├── /v1/face/detect     → 人脸检测
  ├── /v1/face/attribute  → 人脸属性 + 情绪 (一站式)
  └── /v1/face/emotion    → 情绪识别 (直调)
```

---

## 二、REST API

### 2.1 人脸检测 — `POST /v1/face/detect`

```bash
curl -X POST http://localhost:8080/v1/face/detect \
  -H "Content-Type: application/json" \
  -d '{
    "image_base64": "<base64>",
    "tile_width": 320,
    "tile_height": 320,
    "overlap_ratio": 0.3,
    "confidence_threshold": 0.3,
    "nms_threshold": 0.45
  }'
```

**参数:**

| 参数 | 默认 | 教室推荐 | 说明 |
|------|------|---------|------|
| `image_base64` | 必填 | — | 图片 base64 |
| `tile_width` | 640 | **320** | 分块宽度 |
| `tile_height` | 640 | **320** | 分块高度 |
| `overlap_ratio` | 0.2 | **0.3** | 分块重叠 |
| `confidence_threshold` | 0.5 | **0.3** | 置信度阈值 |
| `nms_threshold` | 0.45 | 0.45 | NMS 阈值 |

**响应:**
```json
{
  "code": 0,
  "data": {
    "faces": [
      {"bbox": [1205, 309, 28, 34], "confidence": 0.625}
    ],
    "tileInfo": {"totalTiles": 62}
  }
}
```

### 2.2 属性 + 情绪 — `POST /v1/face/attribute`

```bash
curl -X POST http://localhost:8080/v1/face/attribute \
  -H "Content-Type: application/json" \
  -d '{"image_base64": "<base64>"}'
```

**响应:**
```json
{
  "code": 0,
  "data": {
    "attributes": [{
      "age": 13,
      "gender": 1,
      "mask": false,
      "emotion": {"label": "开心", "probability": 0.75}
    }]
  }
}
```

### 2.3 情绪直调 — `POST /v1/face/emotion`

```bash
curl -X POST http://localhost:8080/v1/face/emotion \
  -H "Content-Type: application/json" \
  -d '{"image_base64": "<人脸裁剪图 base64>"}'
```

**响应:**
```json
{
  "code": 0,
  "data": {
    "emotion": 4,
    "label": "开心",
    "probabilities": [0.01, 0.02, 0.03, 0.05, 0.75, 0.08, 0.04, 0.02]
  }
}
```

---

## 三、情绪标签 (8 类)

| ID | 中文 | 英文 |
|----|------|------|
| 0 | 愤怒 | Anger |
| 1 | 蔑视 | Contempt |
| 2 | 厌恶 | Disgust |
| 3 | 恐惧 | Fear |
| 4 | 开心 | Happy |
| 5 | 中性 | Neutral |
| 6 | 伤心 | Sad |
| 7 | 惊讶 | Surprise |

---

## 四、服务地址

| 服务 | 端口 |
|------|------|
| VisionMind REST API | 8080 |
| face_server (gRPC) | 50053 |
| emotion_server (gRPC) | 50057 |
| attribute_server (gRPC) | 50058 |
