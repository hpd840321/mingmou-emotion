# 完整数据处理管线 — 实现汇总

## 新增/修改文件

### Phase 3: gRPC 集成
| 文件 | 说明 |
|------|------|
| `pom.xml` | 添加 gRPC (netty-shaded, protobuf, stub) + protobuf-maven-plugin |
| `src/main/proto/inference.proto` | 人脸服务 proto 定义 |
| `GrpcFaceServiceClient.java` | gRPC 客户端，直连 face_server:50053，调用 Analyze/Search/Compare RPC |

### Phase 4: 检测 + 抠图 + 注册
| 文件 | 说明 |
|------|------|
| `FaceCroppingService.java` | Java BufferedImage 抠图 + 30% 扩边，按学校/班级/日期/时段存储 |
| `FaceRegistrationService.java` | 注册裁剪人脸到 VisionMind faceDB |
| `FaceProcessingPipeline.java` | 主管线编排：读取 PENDING 图片 → gRPC Analyze → 抠图 → 注册 → 表情 |
| `AdminController.java` | 新增 `POST /api/v1/admin/pipeline/run` 触发入口 |
| `FaceRecord.java` | 新增 croppedImageUrl, isRegisteredToLib, libFaceId, libRegisterStatus 字段 |

### Phase 5: Qdrant 向量聚类
| 文件 | 说明 |
|------|------|
| `FaceClusteringServiceV2.java` | Qdrant scroll 获取所有 128维向量 → 余弦相似度建图 → BFS 连通分量聚类 → 写入 face_cluster |

### Phase 6: 多维统计
| 文件 | 说明 |
|------|------|
| `EmotionStatisticsService.java` | 按班级×日期聚合情绪分布，计算 KPI (positive_ratio, engagement_score)，定时每10分钟运行 |

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/admin/pipeline/run` | 触发完整处理管线（异步） |

## 调用链

```
POST /api/v1/admin/pipeline/run
  → FaceProcessingPipeline.processAll()
    → gRPC face_server:50053 Analyze (detect + attribute + emotion)
    → FaceCroppingService (BufferedImage crop)
    → FaceRegistrationService (POST /v1/facedb/register)
    → EmotionRecord save
    → class_image status = COMPLETED

FaceClusteringServiceV2 (每1h定时 / API触发)
  → Qdrant scroll face_features
  → Cosine similarity matrix
  → BFS connected components
  → face_cluster save

EmotionStatisticsService (每10min定时 / API触发)
  → 读取所有 EmotionRecord
  → 按 class_id × date 聚合
  → EmotionAggregation save
```

## 关键配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `app.face.grpc.host` | face-1 | gRPC 服务地址 |
| `app.face.grpc.port` | 50053 | gRPC 服务端口 |
| `app.face.confidence-threshold` | 0.3 | 人脸置信度阈值 |
| `app.face.crop-margin` | 0.3 | 抠图扩边比例 |
| `app.clustering.similarity-threshold` | 0.7 | 聚类相似度阈值 |
| `app.clustering.min-cluster-size` | 3 | 最小簇大小 |
