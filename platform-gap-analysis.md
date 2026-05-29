# 平台功能缺陷分析报告

> 明眸学生情绪感知平台 — 2026-05-29 评估

---

## 平台能力现状总览

```
Frontend (Vue3)          Backend (Spring Boot)         Engine (C++ gRPC)
───────────────          ────────────────────          ──────────────────
8 pages                 12 REST Controllers            InspireFace (TensorRT)
· 学校总览               · SchoolController            · 人脸检测 (Detect)
· 班级仪表盘             · ClassController             · 人脸识别 (Search)
· 座位热力图             · StudentController            · 人脸比对 (Compare)
· 学生画像               · AdminController              · 特征提取
· 学校树                 · SchoolTreeController         · 属性分析 (gender/age)
· 人脸聚类               · FaceClusterController        · 表情分析 (EmotiEffLib)
· 预警规则               · AlertController              · 人脸库管理 (Qdrant)
· 管理后台               · InterventionController
                         · ImageIngestController
                         · FaceController (V端)
                         12 DB tables (PostgreSQL)
                         Redis Streams · Kafka · Qdrant
```

---

## 1. 基础设施层缺陷

### 1.1 后端应用无法稳定运行

| 问题 | 影响 | 根因 |
|------|------|------|
| `DataScanRunner` 使用 `CompletableFuture.runAsync()` | 异步任务在ForkJoinPool守护线程中执行，CommandLineRunner返回后JVM退出 | 设计缺陷：异步启动无生命周期管理 |
| 应用启动后立即关闭 | 无法启动后端服务，AI流水线 `ImageProcessingOrchestrator` 无法轮询处理 | JVM关闭钩子在启动后3ms触发 |

### 1.2 引擎批量处理退化

| 问题 | 影响 | 根因 |
|------|------|------|
| VisionMind face_server 200次请求后挂起 | ~1078张人脸未处理 | C++ gRPC服务资源泄漏 / 健康检查降级 |
| 503错误频繁 | 大批量处理不可靠 | GpuInstanceRegistry 健康检查与真实服务状态不同步 |

### 1.3 部署割裂

- `emotion-platform` (Spring Boot) 和 `visionmind-api` 是两套独立的 Java 项目
- docker-compose 配置与实际运行的服务不一致（端口、镜像名不匹配）
- 无统一的健康检查和运维面板

---

## 2. 数据采集层缺陷

### 2.1 图片文件名解析脆弱

`DataDirectoryScanner` 依赖 `data/{school}/{class}/{YYYY-MMDD}/{period}/*.jpg` 目录结构和文件名 `YYYYMMDDHHmmss_*.jpg` 格式。任何命名偏离或结构变化都会导致导入失败。

### 2.2 无实时采集能力

- 当前仅支持从 `data/` 目录批量扫描导入
- 无实时摄像头推流接入
- `ImageIngestController` 支持单张上传但前端无对应交互

### 2.3 无数据源管理

- 不能配置多个数据源
- 无法按时间范围选择性导入
- 无法处理重复/增量数据（仅靠 `image_url` 去重）

---

## 3. AI 处理层缺陷

### 3.1 置信度管理缺失（已部分修复）

| 缺陷 | 状态 | 说明 |
|------|:----:|------|
| 人脸检测无置信度阈值过滤 | ✅ 已修复 | 可配置 `app.face.confidence-threshold` |
| 人脸质量分未透传 | ❌ 引擎限制 | TileDetect 路径未计算 `HFFaceQualityDetect` |
| NMS 阈值硬编码 | ⚠️ 外部端点 | `/v1/face/detect` 硬编码 0.45，不支持调用方配置 |

### 3.2 情绪分析严重受限

- **EmotiEffLib TRT 引擎未成功加载**：全部 1328 条 emotion_record 来自 `/v1/face/attribute` 的 `expression` 字段，该字段原始置信度值（2.3-3.8）看起来是未归一化的 logits 而非概率 (0-1)
- **未归一化置信度**：无 softmax 归一化，跨图片不可比
- **仅存 dominant 值**：独立情绪维度概率 (`happy`, `sad`, `angry` 等) 全部为 null
- **仅分析第一张人脸**：多人在一张图片中时，只有置信度最高的人脸被分析

### 3.3 人脸特征/聚类粗粒度

- 注册到 VisionMind faceDB 的人脸（1299张）存在 Qdrant，但：
  - **不可靠注册率**：约5%注册失败（"未检测到人脸，无法提取特征"）
  - **无聚类流水线**：`FaceClusteringService` 仅按 token 前8字符粗哈希聚类，非特征向量相似度
  - **无自动标注**：聚类结果需人工手动标注（`FaceClusterPage.vue`），但标注时传空图片 (`new byte[0]`)
- **Qdrant 向量未被充分利用**：已注册 1299 张人脸，但无自动聚类查询

### 3.4 分析引擎配置不透明

| 参数 | 位置 | 可配置性 |
|------|------|:--------:|
| 检测模型 | 编译时确定性 (Megatron Pack) | ❌ 不可运行时更换 |
| 最小人脸尺寸 | 编译时 160px | ❌ 硬编码 |
| 最大检测数 | Analyze: 50, TileDetect: 20 | ❌ 硬编码 |
| 置信度阈值 | Dockerfile 无配置 | ❌ 需重建容器 |
| 情绪引擎 | Dockerfile 复制 .engine 文件 | ❌ 文件缺失/不兼容 |

---

## 4. 数据存储层缺陷

### 4.1 聚合数据空洞

`EmotionAggregationService.aggregate()` 按 `studentId + date + periodId` 聚合，但：
- 前置条件 `faceRecordRepository.findByStudentId(studentId)` 依赖于 `face_record.student_id` 被设置
- 而 `face_record.student_id` 仅通过 `FaceLibraryService.annotateCluster()` 手动标注设置
- **实际上所有 2406 条 face_record 的 student_id 均为 NULL** → 聚合永远产生 0 条记录
- **仪表盘 KPI 数据为空**

### 4.2 核心查询空转

```java
// ClassController.heatmap() — 实际未按班级/日期过滤
var images = classImageRepository.findAll();

// StudentController.emotion-report() — 依赖 aggregation 数据
var aggs = aggregationRepository.findByStudentIdAndDateBetween(id, startDate, endDate);
// → 因 student_id 为空，结果为空
```

### 4.3 无学生-人脸映射

- `face_record.student_id` 是 FK 到 `student` 表，但从未自动填充
- 无自动标注流程（将未知人脸关联到已有学生）
- 即使 `EmotionRecognitionService` 返回了情绪，`saveResults()` 也将 `face_record.student` 设为 null

### 4.4 情绪维度数据丢失

`EmotionRecord` 实体定义 7 个维度字段 (`emotionHappy`, `emotionSad` … `emotionNeutral`)，但 `saveResults()` 仅保存 `dominantEmotion` 和 `dominantConfidence`，所有维度概率为 NULL。

---

## 5. API 层缺陷

### 5.1 外部端点功能不全

| 端点 | 问题 |
|------|------|
| `/v1/face/detect` | 缺少 quality, mask, liveness 字段 |
| `/v1/face/attribute` | 调用方无法选择分析特性（硬编码 0xFF — 全部） |
| `/v1/facedb/register` | 无批量注册接口 |
| `/v1/face/search` | 无聚类/分组查询接口 |

### 5.2 内部端点未暴露

| 内部端点 (`/api/v1/face/`) | 外部版本 (`/v1/face/`) | 差异 |
|---|---:|---|
| `/detect` 支持自定义 tileWidth/confThreshold/NMS | `/detect` 硬编码参数 | ⚠️ |
| `/detect` 返回 quality/mask/liveness | `/detect` 仅返回 bbox+confidence | ❌ |

---

## 6. 前端展示缺陷

### 6.1 页面功能与实际数据脱节

| 页面 | 依赖的数据 | 实际状态 |
|------|-----------|:--------:|
| SchoolOverview | `EmotionAggregation` + student 关联 | ❌ 全部为空 |
| ClassDashboard | `EmotionAggregation` 按学生粒度 | ❌ 无数据 |
| SeatHeatmap | 座位排布配置 + student 关联 | ❌ 无数据 |
| StudentProfile | `EmotionAggregation` 按学生 | ❌ 无数据 |
| FaceClusterPage | `FaceCluster` 记录 | ❌ 0 条聚类 |

### 6.2 缺少关键功能

| 功能 | 说明 |
|------|------|
| **实时监测** | 无 WebSocket 实时推送检测结果 |
| **历史趋势对比** | 无周/月/学期维度对比 |
| **报告导出** | 无 PDF/Excel 导出 |
| **多维度筛选** | 日期、时段、班级、情绪类型交叉筛选 |
| **阈值配置UI** | 置信度阈值、NMS阈值等在 UI 无配置入口 |
| **座位管理** | 无座位编排 UI，`SeatHeatmapData` 中的 `SeatData` 无法填充 |

### 6.3 无权限管理

- 前端 `UserRole` 定义了 7 种角色但未实现鉴权逻辑
- 后端无 Spring Security 配置或 JWT 验证
- 所有 API 端点无权限拦截

---

## 7. 运维监控缺陷

| 缺失项 | 影响 |
|--------|------|
| 日志聚合 (ELK/Loki) | 问题排查需要手动 `docker logs` |
| 指标采集 (Prometheus) | 无法监控处理速率、延迟、错误率 |
| 链路追踪 | 无法追踪单张图片的完整处理链路 |
| 告警通知 | 平台本身产生告警但无外部通知渠道 |
| 批量重试机制 | 失败任务无自动重试队列 |

---

## 缺陷优先级矩阵

| 优先级 | 缺陷 | 影响面 | 修复难度 |
|:------:|------|:------:|:--------:|
| 🔴 P0 | 后端应用无法稳定启动 | 整个平台不可用 | 低 |
| 🔴 P0 | `student_id` 空导致全部聚合/仪表盘数据为空 | 所有前端页面无数据 | 中 |
| 🟠 P1 | 情绪引擎 EmotiEffLib 未加载 | 1328条记录置信度异常 | 高 |
| 🟠 P1 | 置信度未归一化 | 情绪分析数据不可比 | 中 |
| 🟠 P1 | 引擎批量处理退化 | ~1078张未处理 | 高 |
| 🟡 P2 | face_record 各维度情绪概率未存储 | 精细分析能力缺失 | 低 |
| 🟡 P2 | 人脸聚类未基于特征向量 | 聚类结果不可靠 | 高 |
| 🟡 P2 | API 端点质量和能力不对等 | 外部调用方能力受限 | 低 |
| 🟢 P3 | 无实时采集能力 | 仅支持批量离线处理 | 高 |
| 🟢 P3 | 无权限管理 | 安全风险 | 中 |
| 🟢 P3 | 前端页面无数据填充 | 用户体验差 | 中 |
