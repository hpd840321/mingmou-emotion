# Backend — emotion-platform

**Stack:** Java 17 + Spring Boot 3.2.5 + PostgreSQL + JPA + gRPC

## STRUCTURE

```
src/main/java/com/school/emotion/
├── client/          # REST/gRPC 外部客户端 (VisionMind, ExternalPush)
├── config/          # Spring 配置 (Security, WebSocket, Async, JWT)
├── controller/      # REST API 端点
├── service/         # 业务逻辑核心
│   └── ai/          # AI gRPC 客户端
├── repository/      # JPA 数据库访问
├── model/           # 实体 + DTO + 枚举
├── event/           # Spring ApplicationEvent 定义
└── listener/        # 事件监听器
```

## WHERE TO LOOK

| Task | File | Notes |
|------|------|-------|
| 图片导入扫描 | `service/DataDirectoryScanner.java` | data/ 目录全量扫描 + 定时同步 |
| 人脸检测管线 | `service/FaceProcessingPipeline.java` | 逐图检测 + 裁剪 + 注册 + 情绪 |
| Qdrant 聚类 | `service/FaceClusteringServiceV2.java` | 余弦相似度 + BFS 连通分量 |
| 仪表盘数据 | `controller/PipelineStatusController.java` | status, data-dirs, run/stop/reset |
| 学校组织树 | `controller/SchoolTreeController.java` | 年级→班级→学生/人脸树 |
| 聚合统计 | `service/EmotionStatisticsService.java` | 班级×日期情绪 KPI |
| 外部推送 | `client/ExternalEmotionPushClient.java` | updateStudent + AddEmotion |
| WebSocket | `config/WebSocketConfig.java` | STOMP + SockJS |
| JWT 认证 | `config/JwtAuthFilter.java` | Bearer token 验证 |

## UNIQUE CONVENTIONS

- **open-in-view: false**: LAZY 加载需 `@Transactional`
- **gRPC 直连**: `GrpcFaceServiceClient` 直连 face_server:50053 (已有但未启用)
- **Resilience4j visionmind**: 熔断器需宽松阈值 (已设为 50/80%)

## KNOWN ISSUES

- `@Transactional` 自调用不生效 (AOP 代理限制)
- face_server C++ 内存 double-free 日志泛滥
- InspireFace 检测下限 ~80px, 教室人脸仅 ~38px
- 绝对路径转 URL 需 `toImageUrl()` 转换
