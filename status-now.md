# 当前系统状态 — 2026-05-29 23:25

---

## 1. 运行状态

| 组件 | 端口 | 状态 | 说明 |
|------|:----:|:----:|------|
| emotion-platform 后端 | 8090 | ✅ 运行中 | 代码就绪，待引擎修复后触发管线 |
| VisionMind API | 8080 | ✅ 运行中 | 可正常响应请求 |
| MySQL | 192.168.3.12:3307 | ✅ 连接正常 | |
| **face_server (C++ gRPC)** | **50053** | **❌ 已停止** | **所有 Docker 镜像均无法启动** |

## 2. 数据库状态

| 表 | 数量 | 状态 |
|----|:----:|------|
| class_image | 2693 | 2274 PENDING + 419 FAILED |
| face_record | 0 | 待管线处理 |
| student | 0 | 待管线自动创建 |
| emotion_record | 0 | — |
| face_cluster | 0 | 清空中 |
| emotion_aggregation | 0 | 清空中 |

## 3. 核心阻塞

**face_server 所有可用镜像均无法启动** — CUDA 版本不兼容或缺少动态库。

| 镜像 | 大小 | 错误 |
|------|:----:|------|
| latest | 6 GB | CUDA driver 不兼容 (容器CUDA 12.6.85, 主机驱动560.35.05) |
| cuda12.5 | 6 GB | 同上 |
| trt107 | 13 GB | 缺少 liblapack.so.3 |
| trt10.3 | 387 MB | 缺少 libInspireFace.so |

**说明**：容器在 14:02~23:19 期间正常运行且检测到人脸，排查过程中被删除重建，重建后镜像不兼容。

## 4. 已完成工作

| 工作 | 状态 |
|------|:----:|
| 34 单元测试 | ✅ 全部通过 |
| gRPC 客户端 | `GrpcFaceServiceClient` |
| 自动 Student 创建 | `FaceProcessingPipeline.processImage()` |
| 管线 API | `POST /api/v1/admin/pipeline/run` |
| 人脸抠图 | `FaceCroppingService` |
| 人脸库注册 | `FaceRegistrationService` |
| Qdrant 聚类 | `FaceClusteringServiceV2` 每小时自动 |
| 多维聚合 | `EmotionStatisticsService` 每10分钟自动 |
| 旧 Orchestrator 禁用 | `ImageProcessingOrchestrator` 已屏蔽 |

## 5. 引擎修复后

```bash
# 重置 → 触发管线
mysql -h 192.168.3.12 -P 3307 -u root -p123456 \
  -e "UPDATE emotion_platform.class_image SET status='PENDING';"
curl -X POST http://localhost:8090/api/v1/admin/pipeline/run
```
