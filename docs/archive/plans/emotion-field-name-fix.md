# 问题修复记录 — 情绪数据字段名不匹配

> 修复时间: 2026-05-30  
> 文件: `emotion-platform/src/main/java/com/school/emotion/model/dto/EmotionAnalysisResult.java`

## 问题

`EmotionAnalysisResult.fromVmResponse()` 解析 REST 响应时使用错误的字段名，导致情绪数据永远解析为空。

## 修复

| 行 | 修复前 | 修复后 | 说明 |
|----|--------|--------|------|
| 20 | `Map emo = vmData.get("emotion")` | 双路径判断 | 原代码将 Integer 强转为 Map，ClassCastException |
| 24 | `attrs.get(0).get("expression")` | `attrs.get(0).get("emotion")` | REST API 响应 key 是 `emotion`，不是 `expression` |

## 修复后代码

```java
// Path 1: /v1/face/emotion direct endpoint → flat {emotion, label, probabilities}
if (vmData.containsKey("label") && vmData.containsKey("emotion")) {
    emotionData = vmData;
}
// Path 2: /v1/face/attribute endpoint → nested {attributes: [{emotion: {label, probability}}]}
if (emotionData == null && vmData.containsKey("attributes")) {
    var attrs = (List<Map<String, Object>>) vmData.get("attributes");
    if (attrs != null && !attrs.isEmpty()) {
        Object emo = attrs.get(0).get("emotion");  // ← "expression" → "emotion"
        if (emo instanceof Map) {
            emotionData = (Map<String, Object>) emo;
        }
    }
}
```

## REST API 响应格式参考

### /v1/face/attribute (Path 2)
```json
{"attributes": [{"emotion": {"label": "开心", "probability": 0.75}}]}
```

### /v1/face/emotion (Path 1)
```json
{"emotion": 4, "label": "开心", "probabilities": [0.01, 0.02, ...]}
```
