# 问题清单

> 生成时间: 2026-05-30
> 涉及项目: VisionMind deploy/docker (docker-compose), emotion-platform (Java)
> 状态: 待引擎工程自行修正

---

## 问题 1: docker-api-1 无法解析 face-1 主机名 (阻塞)

**严重性:** P0 — 阻塞所有 face/emotion API 调用

**现象:** 所有 REST API (`/v1/face/detect`, `/v1/face/attribute`) 返回 HTTP 503。docker-api-1 日志:

```
io.grpc.ManagedChannelImpl: Failed to resolve name. 
status=UNKNOWN_HOST, description=Unable to resolve host face-1
```

**根因:** `docker-compose.yml` 中 `api` 服务配置了环境变量 `FACE_HOST=face-1` (line 112)，但 Docker DNS 内 `face-1` 不可解析。

实测:
```
docker exec docker-api-1 getent hosts face-1        → ❌ 空 (不解析)
docker exec docker-api-1 getent hosts docker-face-1-1 → ✅ 172.25.0.12
```

face 容器的 DNS 别名只有 `docker-face-1-1` 和容器 ID，缺少服务名 `face-1`。

**修复 (二选一):**

**方案 A (推荐):** 给 face 容器增加 DNS 别名 `face-1`:
```bash
docker network disconnect docker_default docker-face-1-1
docker network connect --alias face-1 docker_default docker-face-1-1
docker restart docker-api-1
```

**方案 B:** 改 compose 中 api 的环境变量 `FACE_HOST=docker-face-1-1` 后重启。

**验证方法:**
```bash
docker exec docker-api-1 getent hosts face-1  # 应返回 172.25.0.12
# 然后:
curl -s -X POST http://localhost:8080/v1/face/detect \
  -H "Content-Type: application/json" \
  -d '{"image_base64":"<base64图片>","tile_width":320,"tile_height":320}' \
  | python3 -m json.tool
# 应返回 code=0 和 faces[]
```

---

## 问题 2: `/v1/face/attribute` 响应字段名与解析器不匹配

**严重性:** P1 — 情绪数据解析为空

**REST API 方案文档定义响应格式:**
```json
{
  "data": {
    "attributes": [{
      "emotion": {"label": "开心", "probability": 0.75}
    }]
  }
}
```

**当前代码 (`EmotionAnalysisResult.fromVmResponse()`) 查找的是:**
```java
Object expr = attrs.get(0).get("expression");  // ❌ 字段名错误
```

应改为:
```java
Object expr = attrs.get(0).get("emotion");      // ✅ 匹配 REST 响应格式
```

**涉及文件:** `emotion-platform/src/main/java/com/school/emotion/model/dto/EmotionAnalysisResult.java`, line 24

---

## 问题 3: `FaceProcessingPipeline` 含多余的 gRPC 情绪代码

> **注意:** 如果你方决定走 REST API 方案（而非 gRPC 直调），则需要回退。

**涉及文件:** `emotion-platform/src/main/java/com/school/emotion/service/FaceProcessingPipeline.java`

当前代码额外注入了 `GrpcFaceServiceClient` 并使用 gRPC `analyze()` 获取情绪。如果使用 REST `/v1/face/attribute`，则需要:

1. 移除 `GrpcFaceServiceClient` 的注入和字段
2. 移除 gRPC 情绪分析代码块 (`grpcClient.analyze()` 调用)
3. 恢复原有的 `visionMindClient.analyzeAttribute()` 调用（但需要在**问题 2**修复后方可生效）

如果决定走 gRPC 直调方案（绕过 REST），则此代码可用，但需确保**问题 1**已修复。

---

## 问题 4: `face_server` C++ 内存 debug 日志泛滥

**严重性:** P3 — 日志噪音，不影响功能

face_server 持续输出:
```
[MEM] double-free blocked (scan) at 0x...
```

这些是 C++ 层的内存调试信息，非故障信号，但会淹没有用日志。

**修复建议:** 修改 face_server 的 `gflags` 或日志级别，关闭 `MEM_DEBUG` 宏。

---

## 环境参考

| 服务 | 地址 | 端口 |
|------|------|------|
| VisionMind REST API | localhost | 8080 |
| face_server (gRPC) | docker-face-1-1 | 50053 |
| emotion_server (gRPC) | emotion | 50057 |
| attribute_server (gRPC) | attribute | 50058 |
| PostgreSQL | 192.168.3.12 | 3307 |
| 情绪标签 (8类) | 0=Anger, 1=Contempt, 2=Disgust, 3=Fear, 4=Happy, 5=Neutral, 6=Sad, 7=Surprise |

---

## 修复优先级建议

| 顺序 | 问题 | 预估耗时 | 依赖 |
|------|------|---------|------|
| 1 | 问题1: DNS 别名 | 2分钟 | — |
| 2 | 问题2: 字段名 | 5分钟 | 问题1 (需验证) |
| 3 | 问题3: 管线代码清理 | 10分钟 | 问题1+2 |
| 4 | 问题4: 日志级别 | 可选 | — |
