# PROJECT KNOWLEDGE BASE

**Generated:** 2026-05-31
**Branch:** main
**Stack:** Java 17 + Spring Boot 3.2.5 / Vue 3 + TypeScript / C++ gRPC (InspireFace TRT)

## OVERVIEW

明眸学生情绪感知平台 — 课堂人脸检测 + 情绪分析系统。摄像头拍照 → InspireFace 人脸检测 → EmotiEffLib 情绪识别 → Qdrant 聚类 → 前端仪表盘展示。

## STRUCTURE

```
├── emotion-platform/    # Spring Boot 后端 (92 Java 源文件)
│   ├── client/          # REST/gRPC 外部 API 客户端
│   ├── config/          # Spring Security/WebSocket/Async/Resilience4j
│   ├── controller/      # REST 控制器
│   ├── service/         # 业务逻辑 (管线、聚类、聚合、推送)
│   │   └── ai/          # AI 服务封装 (gRPC, 聚类)
│   ├── repository/      # JPA 数据访问
│   ├── model/           # 实体 + DTO + 枚举
│   ├── event/           # Spring ApplicationEvent
│   └── listener/        # 事件监听器
├── emotion-frontend/    # Vue 3 + Vite 前端 (48 源文件)
│   ├── views/           # 页面组件
│   ├── api/             # Axios API 客户端
│   ├── components/      # 通用组件
│   ├── stores/          # Pinia 状态管理
│   └── router/          # 路由配置
├── data/                # 课堂图片源文件 (4560 张)
├── docs/                # 设计文档和报告
└── docker-compose.yml   # VisionMind C++ 引擎容器编排
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| 人脸检测管线 | `service/FaceProcessingPipeline.java` | 单图处理 + 批量处理 |
| 聚类分析 | `service/FaceClusteringServiceV2.java` | Qdrant BFS 余弦相似度聚类 |
| 外部推送 | `client/ExternalEmotionPushClient.java` | 推送情绪到 ylcs.htface.cn |
| 年级/班级树 | `controller/SchoolTreeController.java` | 学校组织树 + 原始数据 |
| 仪表盘状态 | `controller/PipelineStatusController.java` | 管线进度 + pendingReal |
| 情绪聚合 | `service/EmotionStatisticsService.java` | 按 class×date 聚合 KPI |
| WebSocket | `config/WebSocketConfig.java` | STOMP 实时推送 |
| Docker 引擎 | `docker-compose.yml` | face/emotion/attribute 容器 |

## CONVENTIONS

- **实体**: JPA `@Entity` + `@Table`, 不使用 Lombok (手写 getter/setter)
- **DTO**: 手写 getter/setter, 不使用 Lombok
- **依赖注入**: Constructor injection (non-`final` 字段用 `@Autowired(required=false)`)
- **事务**: `@Transactional` 在 Service 层, 注意自调用 AOP 代理失效
- **REST API**: 统一响应格式 `{code: 0, message: "...", data: ...}` 
- **Resilience4j**: 熔断器配置 `visionmind` circuit breaker (需要宽松阈值)
- **Flyway**: 禁用 (`flyway.enabled=false`), 手动管理 DDL
- **gRPC**: `io.grpc` + protobuf, 直连 face_server:50053

## ANTI-PATTERNS

- **`@Transactional` 自调用**: `scheduledAggregation()` 调用 `aggregateByClassAndDate()` → `@Transactional` 不生效
- **CircutBreaker 过度敏感**: 默认 window=10, threshold=50% 太灵敏, 已改为 50/80%
- **绝对路径存储**: `croppedImageUrl` 存文件系统绝对路径 → 需 `toImageUrl()` 转为 URL
- **`console.log` 残留**: PipelineMonitor.vue 有调试日志, 生产应移除
- **`face_classId` 硬编码**: FaceClusteringServiceV2 曾用 `fc.setClassId(0L)`
- **PROCESSING 状态卡死**: `processImage()` 先写 DB 再干活 → kill 后状态残留 (已加 `@PostConstruct` 重置)

## COMMANDS

```bash
# 后端
cd emotion-platform && mvn clean package -DskipTests
java -jar target/emotion-platform-0.1.0-SNAPSHOT.jar --server.port=8090 --spring.profiles.active=dev

# 前端
cd emotion-frontend && npm run dev

# 登录
POST http://localhost:8090/api/v1/auth/login  {"username":"admin","password":"123456"}
```

## NOTES

- `face_server` C++ 内存有 `double-free` 问题 (`[MEM] double-free blocked`), 长时间运行后可能 gRPC 断连
- InspireFace 模型对 <80px 人脸无法检测, 教室 2560×1920 图片人脸 ~38px
- 定时同步: `DataDirectoryScanner.scheduledSync()` 每 5 分钟扫描 data/ 目录
- 聚类定时: `FaceClusteringServiceV2.scheduledAutoAnnotate()` 每 1 小时
- 聚合定时: `EmotionStatisticsService.scheduledAggregation()` 每 10 分钟 (需 `@Transactional`)
