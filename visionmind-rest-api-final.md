# VisionMind REST API — 表情识别平台接口 (最终版)

> 版本: v2.0 | 更新: 2026-05-30

---

## 一、服务地址

| 服务 | 地址 |
|------|------|
| VisionMind REST API | `http://localhost:8080` |

所有接口使用 `POST` 方法，`Content-Type: application/json`。

---

## 二、人脸检测 — `/v1/face/detect`

**说明:** 对大图进行分块人脸检测，返回所有人的脸框位置。

**请求:**
```json
{
  "image_base64": "<base64>",
  "tile_width": 320,
  "tile_height": 320,
  "overlap_ratio": 0.3,
  "confidence_threshold": 0.3,
  "nms_threshold": 0.45
}
```

| 参数 | 类型 | 默认 | 教室推荐 | 说明 |
|------|------|------|---------|------|
| `image_base64` | string | 必填 | — | base64 图片 |
| `tile_width` | int | 640 | 320 | 分块宽 |
| `tile_height` | int | 640 | 320 | 分块高 |
| `overlap_ratio` | float | 0.2 | 0.3 | 重叠比 |
| `confidence_threshold` | float | 0.5 | 0.3 | 置信阈值 |
| `nms_threshold` | float | 0.45 | 0.45 | NMS 阈值 |

**响应 (检测到人脸):**
```json
{
  "code": 0,
  "data": {
    "success": true,
    "faces": [
      {"bbox": [1205, 309, 28, 34], "confidence": 0.625, "quality": 0.85, "mask": false, "liveness": 0.92},
      {"bbox": [424, 521, 52, 65], "confidence": 0.596}
    ],
    "tileInfo": { "totalTiles": 62 }
  }
}
```

**响应 (未检测到):**
```json
{
  "code": 0,
  "data": { "success": false, "faces": [], "tileInfo": { "totalTiles": 62 } }
}
```

---

## 三、情绪识别 (单张) — `/v1/face/emotion`

**说明:** 对单张已裁剪的人脸图片进行情绪识别。

**请求:**
```json
{
  "image_base64": "<人脸裁剪图 base64>"
}
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

## 四、情绪识别 (批量) — `/v1/face/emotion/batch`

**说明:** 对多张已裁剪的人脸图片批量进行情绪识别，结果数组与输入数组按索引一一对应。

**请求:**
```json
{
  "images": ["<人脸裁剪图1 base64>", "<人脸裁剪图2 base64>", "..."]
}
```

**响应:**
```json
{
  "code": 0,
  "data": {
    "results": [
      {"emotion": 4, "label": "开心", "probabilities": [0.01,0.02,0.03,0.05,0.75,0.08,0.04,0.02]},
      {"emotion": 5, "label": "中性", "probabilities": [0.10,0.05,0.08,0.02,0.15,0.50,0.05,0.05]},
      {"emotion": 4, "label": "开心", "probabilities": [0.02,0.03,0.01,0.04,0.80,0.05,0.03,0.02]}
    ]
  }
}
```

**性能:** 30 张图约 30-50ms（并发 gRPC 调用，GPU 流水线并行）。

---

## 五、人脸属性 (一站式) — `/v1/face/attribute`

**说明:** 对完整图片进行人脸检测 + 属性 + 情绪，一站式返回。注意：当前仅返回**第一张**检测到的人脸的结果。

**请求:**
```json
{
  "image_base64": "<base64 完整图片>"
}
```

**响应 (检测到人脸):**
```json
{
  "code": 0,
  "data": {
    "attributes": [{
      "age": 13,
      "age_bracket": 13,
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

**响应 (未检测到人脸):**
```json
{
  "code": 0,
  "data": { "attributes": [{}] }
}
```

---

## 六、1:1 人脸比对 — `/v1/face/verify`

```json
// 请求
{ "image_a": "<base64>", "image_b": "<base64>", "threshold": 0.85 }

// 响应
{ "code": 0, "data": { "similarity": 0.92, "same": true } }
```

---

## 七、1:N 人脸搜索 — `/v1/face/search`

```json
// 请求
{ "image": "<base64>", "top_k": 5, "threshold": 0.5 }

// 响应
{ "code": 0, "data": { "results": [{"face_id": "...", "similarity": 0.88, "metadata": {...}}] } }
```

---

## 八、情绪标签 (8 类)

| ID | 中文 |
|----|------|
| 0 | 愤怒 |
| 1 | 蔑视 |
| 2 | 厌恶 |
| 3 | 恐惧 |
| 4 | 开心 |
| 5 | 中性 |
| 6 | 伤心 |
| 7 | 惊讶 |

---

## 九、教室场景推荐调用流程

```
① POST /v1/face/detect
    参数: tile_width=320, tile_height=320, overlap_ratio=0.3, confidence_threshold=0.3
    ← faces[N] = [{bbox, confidence}, ...]

② 按 bbox 裁剪 N 张人脸图 (客户端自行裁剪)

③ POST /v1/face/emotion/batch
    参数: { images: [N张裁剪图] }
    ← results[N] = [{emotion, label, probabilities}, ...]

④ 配对: faces[i].bbox + results[i].emotion → 存入 emotion_record
```

---

## 十、典型 curl 示例

```bash
# 人脸检测
curl -X POST http://localhost:8080/v1/face/detect \
  -H "Content-Type: application/json" \
  -d '{"image_base64":"...","tile_width":320,"tile_height":320,"overlap_ratio":0.3,"confidence_threshold":0.3}'

# 批量情绪
curl -X POST http://localhost:8080/v1/face/emotion/batch \
  -H "Content-Type: application/json" \
  -d '{"images":["<crop1>","<crop2>","<crop3>"]}'

# 单张情绪
curl -X POST http://localhost:8080/v1/face/emotion \
  -H "Content-Type: application/json" \
  -d '{"image_base64":"<crop>"}'
```
