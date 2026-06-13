# 平台完整分析报告 — 2026-05-31

> 范围: 全栈 (Spring Boot + Vue 3 + C++ gRPC 引擎)  
> 方法: 日志分析 + 数据库审计 + API 测试 + 容器检查

---

## 一、运行状态

### 1.1 服务状态

| 组件 | 端口 | 状态 | 说明 |
|------|:----:|:----:|------|
| `emotion-platform` 后端 | 8090 | ✅ 运行中 | Spring Boot v3.2.5, Java 17 |
| `emotion-frontend` 前端 | 5173 | ✅ 运行中 | Vite 开发服务器 |
| `docker-api-1` (VisionMind API) | 8080 | ✅ healthy | REST → gRPC 网关 |
| `docker-face-1-1` (InspireFace) | 50053 | ✅ UP | C++ TensorRT 人脸检测/识别 |
| `docker-emotion-1` (EmotiEffLib) | 50057 | ✅ healthy | 情绪识别 TRT |
| `docker-attribute-1` (GenderAge) | 50058 | ✅ healthy | 性别/年龄 TRT |
| MySQL | 3307 | ✅ 连接正常 | 192.168.3.12:3307 |
| Redis | 6379 | ✅ UP | 消息队列 |
| Qdrant | 6333 | ✅ UP | 人脸特征向量库 |
| SeaweedFS | 8888 | ✅ UP | 文件存储 |
| Kafka | 9092 | ✅ UP | 消息队列 |

### 1.2 DNS 解析

之前阻塞的 `face-1` DNS 问题已自动修复：
```
docker exec docker-api-1 getent hosts face-1
→ 172.25.0.11  face-1  ✅
```

---

## 二、数据库数据状态

### 2.1 class_image

| 状态 | 数量 | 说明 |
|:----:|:----:|------|
| COMPLETED | 7172 | 管线处理完成（含 0 张人脸） |
| FAILED | 80 | 管线处理失败（熔断触发期） |
| PROCESSING | 1 | 正在处理 |
| **合计** | **7253** | |

### 2.2 数据链路

| 表 | 数量 | 说明 |
|----|:----:|------|
| `class_image` | 7253 | 全部图片 |
| `face_record` | 939 | 早期旧数据（新管线检测到 0 张人脸） |
| `emotion_record` | 62 | 早期旧数据的情绪分析（6.6% 的 face_record） |
| `emotion_aggregation` | 2 | 按 student_id 聚合（但 student_id=0） |
| `student` | 0 | 已全部删除 |
| `face_cluster` | 0 | 已全部删除 |

### 2.3 数据流断裂点

```
拍照 (2560×1920) 
  → class_image (7253 张)
  → pipeline 调用 /v1/face/detect
  → InspireFace TileDetect
  ❌ 检测到 0 张人脸 (模型下限 80px > 教室人脸 38px)
  → 无 face_record 产出
  → 无 emotion_record 产出
  → 无聚合数据
  → 仪表盘无数据
```

---

## 三、人脸检测问题根因

### 3.1 现场测试结果

**测试图片路径:**
```
/home/zebra/Downloads/官渡一中初一班-0526/data/官渡一中/初一班/2026-05-28/第3节/172_16_15_11_{20260528_091904}.jpg
```

**参数对比测试:**

| 测试场景 | 图片尺寸 | API | 结果 |
|---------|:--------:|:---:|:----:|
| 裁剪人脸 | 48×48 | `/v1/face/attribute` | ✅ 检测到 1 张人脸, age=24, gender=女 |
| 教室全景 | 2560×1920 | `/v1/face/detect` tile=320 | ❌ 超时 (>180s) |
| 教室全景 | 2560×1920 | `/v1/face/detect` tile=640 | ❌ 超时 (>180s) |
| 教室全景 | 2560×1920 | `/v1/face/attribute` | ❌ 超时 (>60s) |
| 教室全景（缩放） | 800×600 | `/v1/face/detect` tile=640 | ❌ 超时 (>30s) |

### 3.2 根因分析

| 因素 | 数值 | 来源 |
|------|:----:|------|
| 图片分辨率 | 2560×1920 | 教室监控摄像头 |
| 教室人脸尺寸 | ~38×38 px | 分析文档 |
| InspireFace 有效检测下限 | ~80px | face_server 引擎限制 |
| `detectPixelLevel` 参数 | 80（已调至最低） | face_server 配置 |
| TileDetect 单张耗时 | >2 分钟 | 172 个 tiles × 每个 >700ms |

**结论：** InspireFace 模型架构限制，对 <80px 的小脸无法检测。这不是参数调整能解决的问题，需要模型级或硬件级方案。

### 3.3 容器日志异常

```
face_server (C++) 持续输出:
[MEM] double-free blocked (scan) at 0x7e81cea467a0
[MEM] double-free blocked (scan) at 0x7e81cea65ec0
...
```

C++ 层存在内存 double-free 问题，虽标注为"不影响功能"，但在高负载下可能导致 gRPC `UNAVAILABLE: io exception` 错误。

---

## 四、已解决的问题

| # | 问题 | 修复方式 | 文件 |
|:--:|------|---------|:----:|
| 1 | 熔断器过于敏感 (10次中5次失败即打开10分钟) | 改为 50次中40次失败才打开，恢复时间 30s | `application.yml` |
| 2 | 图片 URL 是本地绝对路径，前端无法加载 | 新增 `WebConfig` 静态映射 `/images/**` | `WebConfig.java` |
| 3 | Vite 未代理 `/images` 路径 | 新增 proxy 规则 `/images` → `:8090` | `vite.config.ts` |
| 4 | 学校树人脸无缩略图 | 新增 `toImageUrl()` 转换路径, `sampleImages` 字段 | `SchoolTreeController.java`, `SchoolTree.vue` |
| 5 | ECharts legend 多于 series 报错 | 删除多余的 legend 项 | `SchoolTree.vue` |
| 6 | 残留 console.log 调试日志 | 移除两处 `console.log` | `PipelineMonitor.vue` |
| 7 | 日期目录 `YYYY-MM-DD` 不被扫描 | 正则改为 `(\\d{4})-(\\d{2})-?(\\d{2})` | `DataDirectoryScanner.java` |
| 8 | 文件名前缀 `172_16_15_11_{` 不被识别 | 正则改为 `.*(\\d{4})(\\d{2})(\\d{2})_?...` | `DataDirectoryScanner.java`, `ImageImportService.java` |
| 9 | 1867 张图片未导入 | 修复文件名/日期正则后重新导入 | — |
| 10 | `Student` 表 3513 条冗余记录 | 全部删除 | — |
| 11 | `autoAnnotateClusters()` 重复创建 Student | 加入 `cluster.getStudentId() != null` 检查 | `FaceClusteringServiceV2.java` |
| 12 | `ImageImportService` 缺少 `class_id` 外键 | 传入 `SchoolClassRepository` 解析 | `ImageImportService.java` |
| 13 | 管线并发处理重复 | `processImage()` 入口重新读取 DB 状态检查 | `FaceProcessingPipeline.java` |
| 14 | `@Transactional` 自调用导致 LazyInitializationException | 移到 `scheduledAggregation()` 方法 | `EmotionStatisticsService.java` |
| 15 | `ExternalEmotionPushRecord` JSON 字段名不匹配 | 添加 `@JsonProperty` 注解 | `ExternalEmotionPushRecord.java` |
| 16 | `EmotionAnalysisResult.EMOTION_KEYS` 索引顺序错误 | 改为引擎实际输出顺序 | `EmotionAnalysisResult.java` |
| 17 | 无定时目录同步机制 | 新增 `@Scheduled(fixedDelay=5min)` | `DataDirectoryScanner.java` |
| 18 | 卡住 PROCESSING 状态 | 新增 `@PostConstruct resetStaleProcessingImages()` | `FaceProcessingPipeline.java` |

---

## 五、未解决的问题

### 5.1 核心阻塞：人脸检测 0 结果

**影响范围：** 所有仪表盘（SchoolOverview, ClassDashboard, StudentProfile）均无数据

**严重性：** P0 — 平台核心功能不可用

**根因：** InspireFace 模型无法检测 <80px 的小脸

**可能的解决方案：**

| 方案 | 说明 | 可行性 | 工作量 |
|------|------|:------:|:------:|
| A: TileDetect 引擎参数调优 | 修改 face_server C++ 代码中的 tile 参数 | 中 | 大 |
| B: 更换更高分辨率摄像头 | 使人脸像素 > 80px | 高 | 硬件投入 |
| C: 图片 AI 放大 (ESRGAN) | pipeline 中先用 AI 放大图片再检测 | 中 | 中 |
| D: 更换检测模型 (YOLO/RetinaFace) | 替换 InspireFace | 高 | 很大 |

### 5.2 `face_server` 内存泄漏

**现象：** `[MEM] double-free blocked` 日志持续输出

**影响：** 长时间运行后可能 gRPC 连接失败

**修复：** 需要 C++ 工程团队修复内存管理

### 5.3 无 student_id 关联

所有 939 条 `face_record` 的 `student_id = NULL`，导致 `EmotionAggregationService` 无法聚合数据。即使引擎修复后产生了新的 face_record，也需要人脸搜索/匹配来自动关联学生。

### 5.4 无实时采集

当前仅支持 `data/` 目录批量导入，不支持实时摄像头推流。

---

## 六、数据库访问信息

```
Host:     192.168.3.12
Port:     3307
Database: emotion_platform
User:     root
Password: 123456
```

## 七、关键测试数据路径

```
教室源图:  /home/zebra/Downloads/官渡一中初一班-0526/data/官渡一中/初一班/2026-05-28/第3节/172_16_15_11_{20260528_091904}.jpg
           (2560×1920, ~1MB, 教室全景)

裁剪人脸:  /home/zebra/Downloads/官渡一中初一班-0526/emotion-platform/images/cropped/官渡一中/初一班/2026-05-29/afterclass/face_127.jpg
           (~48px, 裁剪人脸 - 可被检测到)

API 测试:
  curl -X POST http://localhost:8080/v1/face/detect \
    -H "Content-Type: application/json" \
    -d '{"image_base64":"<base64>","tile_width":320,"tile_height":320}'

  curl -X POST http://localhost:8080/v1/face/attribute \
    -H "Content-Type: application/json" \
    -d '{"image_base64":"<base64>"}'

  curl -X POST http://localhost:8080/v1/face/emotion \
    -H "Content-Type: application/json" \
    -d '{"image_base64":"<base64>"}'

登录:
  POST http://localhost:8090/api/v1/auth/login
  {"username":"admin","password":"123456"}

启动管线:
  POST http://localhost:8090/api/v1/admin/pipeline/run
  (需要 Bearer token)
```

---

*报告生成时间: 2026-05-31 18:30 | 由 Sisyphus 自动生成*
