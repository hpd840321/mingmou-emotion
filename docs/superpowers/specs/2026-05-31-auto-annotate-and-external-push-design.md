# 自动标注 & 外部推送 — 设计方案

> 日期: 2026-05-31 | 状态: 待实现

---

## 一、背景与目标

### 1.1 当前问题

- 聚类产生的 `face_cluster` 需人工标注才能创建 Student 记录，导致 `face_record.student_id` 全部为 NULL
- `EmotionAggregationService.aggregate()` 依赖 `student_id` 进行聚合，因此仪表盘（SchoolOverview/ClassDashboard）无数据
- `face_server`/`emotion_server` 引擎损坏，情绪分析 pipeline 无法产出数据

### 1.2 目标

1. **自动标注**：聚类完成后自动创建 Student + 回填 student_id，使仪表盘数据链路走通
2. **可重命名**：默认名 `student001`/`student002`...，用户可在 FaceClusterPage 改名为真实姓名（纯展示变更）
3. **外部推送**：将学生信息和情绪数据推送到 `ylcs.htface.cn:33895/api/Page/Execute`
4. **分阶段**：自动标注立即实现；外部推送 Client/Service 先写好代码，待引擎恢复后生效

---

## 二、自动标注 (FaceClusteringServiceV2)

### 2.1 变更点

`FaceClusteringServiceV2.runClustering()` 在保存 cluster 后新增 `autoAnnotateClusters()`：

```
runClustering()
  → scrollAllPoints()        Qdrant 取向量
  → 余弦相似度 + BFS 聚类    生成分组
  → save face_cluster        写库 (status = "auto_annotated")
  → ★ autoAnnotateClusters() 遍历每个 cluster 自动标注
      → 创建 Student
      → 回填 face_record.student_id
      → 推送 updateStudent (调用 ExternalEmotionPushService)
```

### 2.2 Student 生成规则

| 字段 | 生成规则 | 示例 |
|------|---------|------|
| `student_no` | `auto_{classId}_{clusterId}` | `auto_1_42` |
| `name` | `student{序号}`（3位，classId 内自增） | `student001` |
| `class_id` | `cluster.classId` | `1` |
| `status` | `"active"` | — |

**序号生成：** `SELECT COUNT(*) FROM student WHERE student_no LIKE 'auto\_${classId}\_%'` → +1 → 3 位 padded。

### 2.3 student_id 回填

cluster 的 `face_tokens` 存储了 `["qcluster_a1b2c3d4_face_1_2_3", ...]` 格式的 `lib_face_id` 列表。

回填逻辑：
```
for each face_token in cluster.face_tokens:
    face_record = faceRecordRepository.findByLibFaceId(face_token)
    if face_record exists and face_record.student_id is NULL:
        face_record.student_id = student.id
        faceRecordRepository.save(face_record)
```

**需要注意的是：**
- 只回填 `student_id` 目前为 NULL 的 face_record（避免覆盖已有标注）
- 同一个 face_record 可能被多个 cluster 引用（理论不会，但做防御性检查）

### 2.4 cluster 状态变更

| 状态 | 含义 | 对应操作 |
|:----:|------|---------|
| `auto_annotated` | 系统自动创建了 Student | runClustering 设置 |
| `renamed` | 用户重命名了 Student 姓名 | FaceClusterController.rename |
| `merged` | 用户手动合并 | 保持现有逻辑 |

### 2.5 FaceClusterVO 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `studentId` | Long | 关联的 Student.id |
| `studentName` | String | 学生名称（默认 `student001`，可重命名） |
| `studentNo` | String | 学号（`auto_1_42`） |
| `autoAnnotated` | boolean | 是否由系统自动标注 |

### 2.6 重命名 API

```
POST /api/v1/face-clusters/{id}/rename
Request: { "studentName": "张三" }
Response: { "code": 0, "data": { "studentId": 42, "studentName": "张三" } }
```

纯改 `Student.name` 字段，不影响 `student_no` 和已回填的 `student_id`。

---

## 三、外部推送 (ExternalEmotionPush)

### 3.1 架构

```
ExternalEmotionPushService
  ├── pushStudent(student)     → 调 updateStudent 接口
  └── pushEmotion(records[])   → 调 AddEmotion 接口

ExternalEmotionPushClient
  └── POST http://ylcs.htface.cn:33895/api/Page/Execute
```

### 3.2 Client 实现

```java
@Component
public class ExternalEmotionPushClient {
    private final RestTemplate restTemplate;
    private final String baseUrl;      // 配置: app.external-push.base-url
    private final String pageId;       // 配置: app.external-push.page-id, 默认 "Emotion"
    private final String cameraCode;   // 配置: app.external-push.camera-code, 默认 "CAM_DEFAULT"

    public PushResult updateStudent(String studentCode, String studentName, List<String> imageUrls) { ... }
    public PushResult addEmotions(List<EmotionPushRecord> emotions) { ... }
}
```

### 3.3 推送触发时机

| 推送方法 | 触发时机 | 阶段 |
|---------|----------|:----:|
| `pushStudent` | 自动标注完成后（聚类时） | 阶段一 ✅ |
| `pushEmotion` | 定时批量（可配置 cron）+ 手动 API 触发 | 阶段二 ⏳ |

### 3.4 字段映射

#### updateStudent 请求

| 字段 | 我方来源 | 说明 |
|------|---------|------|
| `pageID` | `"Emotion"` | 固定 |
| `method` | `"updateStudent"` | 固定 |
| `student_code` | `Student.studentNo` | `"auto_1_42"` |
| `student_name` | `Student.name` | `"student001"`（或重命名后的"张三"） |
| `ImageUrl` | 该 cluster 对应 face_record 的 croppedImageUrl 列表 | 取前 5 张 |

#### AddEmotion 请求 — 单条记录映射

| 对方字段 | 我方来源 | 处理 |
|---------|---------|------|
| `Id` | `EmotionRecord.id` | 直接映射 |
| `CameraCode` | 配置项 | 默认 `"CAM_DEFAULT"` |
| `student_code` | `Student.studentNo` | 通过 face_record.student 关联 |
| `SmallPic` | `FaceRecord.croppedImageUrl` | 裁剪图 URL |
| `CaptureTime` | `ClassImage.captureTime` | 格式化 `yyyy-MM-dd HH:mm:ss` |
| `ImageUrl` | `ClassImage.imageUrl` | 原始大图 URL |
| `Confidence` | `EmotionRecord.dominantConfidence` | `String.valueOf()` |
| `score` | `dominantConfidence × 100` | int 化 |
| `color` | 根据 Emotion 查映射表 | 见下方 |
| `Emotion` | 根据 8 维概率聚合取 dominant | 见下方 |
| `GazeDirection` | — | `""`（暂无数据） |
| `created_at` | `EmotionRecord.createdAt` | 格式化 |

#### Emotion 映射（以引擎实际输出为准）

引擎 `/v1/face/emotion` 返回 `label` 为**中文标签**，直接查表映射：

| 引擎 label | 对方 Emotion | 说明 |
|:----------:|:------------:|------|
| 开心 | `happy` | 直接映射 |
| 伤心 | `sad` | 直接映射 |
| 愤怒 | `angry` | 直接映射 |
| 惊讶 | `surprised` | 直接映射 |
| 恐惧 | `fearful` | 直接映射 |
| 中性 | `calm` | 中性 → 平静 |
| 蔑视 | `calm` | fallback |
| 厌恶 | `angry` | fallback |

不做多维度概率聚合，不需要推导 `anxious`。对方 `anxious` 暂不产生。

#### color 映射表

| 对方 Emotion | color |
|:-----------:|:-----:|
| `happy` | `green` |
| `sad` | `blue` |
| `angry` | `red` |
| `calm` | `cyan` |
| `surprised` | `yellow` |
| `fearful` | `purple` |

### 3.5 手动触发 API

```
POST /api/v1/admin/external-push
Params: type=students|emotions|all
Response: { "code": 0, "data": { "pushed": 150, "failed": 2, "errors": [...] } }
```

用于引擎恢复后全量重推。

---

## 四、涉及文件清单

### 新建文件

| 文件 | 说明 |
|------|------|
| `client/ExternalEmotionPushClient.java` | 封装 RestTemplate 调用外部 API |
| `client/ExternalEmotionPushRecord.java` | 单条推送记录 DTO |
| `service/ExternalEmotionPushService.java` | 推送业务逻辑 |
| `service/ExternalEmotionPushMapper.java` | 8 维概率 → 对方 Emotion/color 映射 |
| `controller/ExternalPushController.java` | 手动触发推送的管理端点 |

### 修改文件

| 文件 | 变更内容 |
|------|---------|
| `service/FaceClusteringServiceV2.java` | 聚类后调用 autoAnnotateClusters() |
| `service/FaceLibraryService.java` | 新增 renameCluster() 方法 |
| `model/dto/FaceClusterVO.java` | 新增 studentId, studentName, studentNo, autoAnnotated |
| `model/entity/FaceCluster.java` | 新增 student_id 外键字段 |
| `repository/FaceRecordRepository.java` | 新增 findByLibFaceId() 方法 |
| `controller/FaceClusterController.java` | 新增 rename 端点 |
| `application.yml` | 新增 app.external-push.* 配置 |
| `FaceClusterPage.vue` | 显示默认名 + 重命名按钮 |
| `api/admin.ts` | 新增 rename API 调用 |

### 新增配置项

```yaml
app:
  external-push:
    enabled: true                           # 总开关
    base-url: http://ylcs.htface.cn:33895   # 外部系统地址
    page-id: Emotion                        # 固定 pageID
    camera-code: CAM_DEFAULT                # 摄像头编码
    batch-size: 200                         # AddEmotion 批量上限
    cron: 0 */5 * * * *                     # 定时推送 cron（默认每5分钟）
```

---

## 五、分阶段计划

### 阶段一（当前可做，引擎不依赖）

**自动标注 + Student 回填：**
1. `FaceClusteringServiceV2.autoAnnotateClusters()` — 自动创建 Student + 回填 student_id
2. `FaceClusterVO` 新增字段 + `FaceCluster` 新增 `student_id`
3. `FaceClusterController.rename()` — 重命名端点
4. `FaceLibraryService.renameCluster()` — 重命名逻辑
5. 前端 `FaceClusterPage.vue` — 显示默认名 + 重命名 UI
6. `ExternalEmotionPushClient` + `Service` — 代码写好，配置关闭
7. `pushStudent` 集成到 autoAnnotate 流程

### 阶段二（引擎恢复后）

1. 启用 `app.external-push.enabled=true`
2. `pushEmotion` 定时批量推送
3. 手动全量重推 API

---

## 六、错误处理

| 场景 | 处理策略 |
|------|---------|
| 外部 API 返回 `success: false` | 记录 warn 日志，不影响本地数据 |
| 外部 API 超时/无响应 | 记录 error 日志，后续定时重试补偿 |
| 部分记录 `failed_ids` 非空 | 记录到 error_log，不阻塞整体流程 |
| pushStudent 失败 | 不影响 autoAnnotate 核心流程（Student 已创建、student_id 已回填） |
