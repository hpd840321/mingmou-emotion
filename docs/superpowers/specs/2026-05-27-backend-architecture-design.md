# 明眸情绪感知平台 — 后端服务架构设计

> 版本: v1.0
> 日期: 2026-05-27
> 技术栈: Spring Boot 4.0 + Spring AI 1.x + PostgreSQL 16 + Redis 7 + Qdrant
> 引擎依赖: VisionMind (REST API + JWT Auth)
> 参考 PRD: `docs/superpowers/specs/2026-05-27-student-emotion-management-platform-prd.md`

---

## 目录

1. [VisionMind 外部接口参考](#1-visionmind-外部接口参考)
2. [系统整体架构](#2-系统整体架构)
3. [完整数据流水线](#3-完整数据流水线)
4. [人脸聚类与人员标注](#4-人脸聚类与人员标注)
5. [聚合分析引擎](#5-聚合分析引擎)
6. [预警引擎](#6-预警引擎)
7. [看板/报表 API 服务](#7-看板报表-api-服务)
8. [WebSocket 通知](#8-websocket-通知)
9. [历史数据导入](#9-历史数据导入)
10. [底库按班级维护](#10-底库按班级维护)
11. [新增数据库表](#11-新增数据库表)
12. [新增文件结构](#12-新增文件结构)
13. [技术决策总结](#13-技术决策总结)

---

## 1. VisionMind 外部接口参考

### 1.1 认证方式

JWT Token 认证。通过 `POST /auth/login` 获取 token，后续请求在 Header 中携带 `Authorization: Bearer <token>`。

### 1.2 统一响应格式

```json
{
  "code": 0,          // 0=成功，非0=失败
  "message": "success",
  "data": { ... }
}
```

### 1.3 接口详表

#### 1.3.1 人脸检测 — `POST /v1/face/detect`

检测图片中所有人脸及位置。

**Request:**
```json
{
  "image_base64": "/9j/4AAQ..."   // Base64 编码的 JPEG 图片
}
```

**Response (200):**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "faces": [
      {
        "bbox": [100, 50, 120, 160],   // [x, y, width, height] 整型像素坐标
        "confidence": 0.95               // 检测置信度
      }
    ]
  }
}
```

**内部检测参数:** maxWidth=640, maxHeight=640, minFaceScale=0.2, nmsThreshold=0.5, scoreThreshold=0.45

#### 1.3.2 人脸属性分析(含表情) — `POST /v1/face/attribute`

分析人脸属性：年龄、性别、口罩、质量、活体、表情。

**Request:**
```json
{
  "image_base64": "/9j/4AAQ...",
  "include": ["age", "gender", "expression", "quality", "liveness"]
}
```

**Response:**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "age": 15,
    "gender": 0,                        // 0=女, 1=男
    "mask": false,                      // 是否戴口罩
    "quality": 0.92,                    // 图片质量分
    "liveness": 0.98,                   // 活体检测分
    "emotion": {
      "label": "happy",                 // 表情标签
      "probability": 0.87               // 概率
    }
  }
}
```

**表情标签枚举:** `happy`, `sad`, `angry`, `surprise`, `fear`, `disgust`, `neutral`

#### 1.3.3 1:N 人脸搜索 — `POST /v1/face/search`

在指定人脸库中搜索相似人脸。

**Request:**
```json
{
  "image": "/9j/4AAQ...",     // Base64 图片
  "top_k": 5,                  // 返回 TopK 结果（默认 5）
  "threshold": 0.5             // 匹配阈值（默认 0.5）
}
```

**Response:**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "results": [
      {
        "id": "face-entry-uuid",
        "name": "张三",
        "extra": "{\"student_id\":\"42\",\"class_id\":\"1\"}",
        "similarity": 0.92
      }
    ]
  }
}
```

#### 1.3.4 1:1 人脸比对 — `POST /v1/face/verify`

比较两张图片是否为同一人。

**Request:**
```json
{
  "image_a": "/9j/4AAQ...",
  "image_b": "/9j/4AAQ...",
  "threshold": 0.85            // 判定阈值（默认 0.85）
}
```

**Response:**
```json
{
  "code": 0,
  "message": "success",
  "data": {
    "similarity": 0.92,
    "same": true                // 基于 threshold 判定
  }
}
```

#### 1.3.5 人脸库列表 — `GET /v1/facedb?page=0&size=10`

列出所有人脸库。

**Response:**
```json
{
  "code": 0,
  "data": [
    {
      "id": "lib-uuid",
      "name": "初一3班-人脸库",
      "description": "学生人脸底库",
      "faceCount": 45
    }
  ]
}
```

#### 1.3.6 人脸注册 — `POST /v1/facedb/register`

将人脸注册到人脸库，关联业务 ID。

**Request:**
```json
{
  "id": "student-001",                  // 外部业务 ID（用学号）
  "name": "张三",
  "extra": "{\"student_id\":\"42\",\"class_id\":\"1\"}",
  "image": "/9j/4AAQ..."                // 人脸图像 Base64
}
```

**Response:** `{ code: 0, data: { id, name, extra, createdAt } }`

#### 1.3.7 人脸查询/更新/删除

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/v1/facedb/{id}` | 查询人脸详情 |
| `PUT` | `/v1/facedb/{id}` | 更新人脸（name/extra/image） |
| `DELETE` | `/v1/facedb/{id}` | 删除人脸 |

---

## 2. 系统整体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                     第三方图片推送 / 历史数据导入                      │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   接入层 (ImageIngestController)                      │
│              POST /api/v1/images/ingest (multipart/form-data)        │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Redis Stream (image:ingest)                      │
│                      异步队列 · 先入先出 · 持久化                      │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│                      ImageProcessingOrchestrator                     │
│                                                                      │
│  ┌────────────────┐    ┌────────────────┐    ┌──────────────────┐   │
│  │ Face Detection │    │ Face Attribute  │    │ 1:N Face Search  │   │
│  │ VM /face/detect│───▶│ VM /face/attr   │───▶│ VM /face/search  │   │
│  └────────┬───────┘    └────────┬───────┘    └────────┬─────────┘   │
│           │                     │                      │             │
│           ▼                     ▼                      ▼             │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    结果持久化                                  │   │
│  │  class_image ← face_detect_record ← emotion_record           │   │
│  │  未匹配人脸 → face_cluster (pending)                          │   │
│  └──────────────────────────┬───────────────────────────────────┘   │
└─────────────────────────────┼───────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                      聚合分析引擎 (Phase 2)                          │
│                                                                      │
│  ┌──────────────────────────────────────────────────┐               │
│  │ EmotionAggregationService                        │               │
│  │  → 监听 ImageProcessedEvent                      │               │
│  │  → 按 (student_id, date, period_id) 增量聚合      │               │
│  │  → UPSERT emotion_aggregation                     │               │
│  │  → 发布 AggregationUpdatedEvent                   │               │
│  └─────────────────────┬────────────────────────────┘               │
│                        │                                            │
│         ┌──────────────┼──────────────┐                              │
│         ▼              ▼              ▼                              │
│  ┌──────────┐   ┌──────────┐   ┌──────────────┐                    │
│  │ 预警引擎  │   │ 报表引擎  │   │ WebSocket     │                    │
│  │ Alert    │   │ Report   │   │ Notification  │                    │
│  │ Engine   │   │ Service  │   │ Service       │                    │
│  └────┬─────┘   └────┬─────┘   └──────┬───────┘                    │
│       │              │                │                              │
│       ▼              ▼                ▼                              │
│  ┌──────────┐   ┌──────────┐   ┌──────────────┐                    │
│  │ alert_log │   │ 聚合数据  │   │ STOMP/WS     │                    │
│  │ 预警记录   │   │ 查询出口  │   │ 实时推送      │                    │
│  └──────────┘   └──────────┘   └──────────────┘                    │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    Dashboard REST API (Phase 2)                     │
│                                                                      │
│  /api/v1/school/overview                   → SchoolOverviewData     │
│  /api/v1/classes/{id}/dashboard            → ClassDashboardData     │
│  /api/v1/classes/{id}/heatmap              → SeatHeatmapData        │
│  /api/v1/students/{id}/emotion-timeline    → StudentProfileData     │
│  /api/v1/face-clusters                     → 待标注聚类列表          │
│  /api/v1/face-clusters/{id}/annotate       → 标注并注册              │
│                                                                      │
│  /ws/class/{classId}/emotion               → 实时推送               │
│  /ws/alerts                                 → 预警推送               │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. 完整数据流水线

### 3.1 实时图片处理流程 (Phase 1)

```
第三方推送 → [1] ImageIngestController (202 ACCEPTED)
              ↓ 保存图片到本地存储 (可配置 SeaweedFS/MinIO)
              ↓ 写入 class_image (PENDING)
              ↓ 推入 Redis Stream
              ↓
           [2] ImageProcessingOrchestrator
              ↓ 更新 status = PROCESSING
              ↓
           [3] VM POST /v1/face/detect
              ↓ 解析 faces[]
              ↓ 对每个人脸:
           [4]   a. 裁剪人脸子图
                  b. 通过 VM /v1/face/search 在班级库中搜索
                  c. 匹配 → 关联 student_id
                  d. 不匹配 → face_cluster 聚类
              ↓
           [5] VM POST /v1/face/attribute 分析表情
              ↓ 写入 face_detect_record + emotion_record
              ↓ 更新 class_image.status = COMPLETED
              ↓
           [6] 发送 ImageProcessedEvent (→ 聚合引擎)
```

### 3.2 图片命名解析

系统现有图片命名格式:
```
IMG_20260526_081500_001.jpg
└──┬──┘└──┬──┘└──┬──┘└─┬─┘
   日期    时间   序号
```

| 字段 | 位置 | 值示例 | 说明 |
|------|------|--------|------|
| date | 5-12 | 20260526 | 拍摄日期 |
| time | 14-17 | 0815 | 拍摄时间 (HHMM) |
| capture_time | 合成 | 2026-05-26T08:15:00+08:00 | ISO 8601 |
| period_label | 所在目录 | 第1节 | 课时段映射 |

### 3.3 目录 → 课时段映射

| 目录名 | period_key |
|--------|-----------|
| 早读-到校 | arrival |
| 第1节 ~ 第8节 | period_1 ~ period_8 |
| 课间操 | recess |
| 午餐-午休 | lunch |
| 课外活动-放学 | afterclass |

---

## 4. 人脸聚类与人员标注

### 4.1 聚类流程

```
未匹配人脸 → [聚类引擎]
               ↓
         1. 提取特征向量 (从 VM face token 获取)
         2. DBSCAN 聚类 (余弦距离, eps=0.4, minPts=2)
         3. 同一聚类同一人 → 分配 cluster_key
         4. 写入 face_cluster (status=pending)
         5. 缓存质心向量用于后续加速匹配
```

### 4.2 聚类引擎触发时机

- 实时：每张图片处理完成后，对其中的未匹配人脸执行增量聚类
- 定时：`@Scheduled(fixedRate = 300000)` 每5分钟对近期新增的未匹配人脸重新聚类

### 4.3 人员标注 API

```
GET  /api/v1/face-clusters?class_id=1&status=pending
  → 返回待标注聚类列表（含缩略图、出现频次、时段分布）

POST /api/v1/face-clusters/{id}/annotate
  Body: { student_name, student_no, class_id }
  → 1. 校验班级存在
  → 2. 创建 student 记录
  → 3. 调用 VM /v1/facedb/register 注册到班级库
  → 4. 回填 cluster 内所有 face_detect_record.student_id
  → 5. 标记 cluster.status = 'annotated'

POST /api/v1/face-clusters/{id}/merge
  Body: { student_id }
  → 将聚类关联到已存在学生
```

### 4.4 人脸状态流转

```
                  ┌──────────┐
                  │ 人脸检测   │
                  └────┬─────┘
                       │
               ┌───────┴───────┐
               ▼               ▼
          ┌──────────┐   ┌──────────┐
          │ 匹配底库   │   │ 未匹配    │
          │ student_id│   │ 无ID     │
          └────┬─────┘   └────┬─────┘
               │              │ 增量聚类
               ▼              ▼
          ┌──────────┐   ┌──────────────┐
          │ 已识别    │   │ face_cluster  │
          │ 直接关联   │   │ (pending)     │
          └──────────┘   └──────┬───────┘
                                │ 人工标注
                                ▼
                          ┌──────────────┐
                          │ 已标注        │
                          │ → 注册底库    │
                          │ → 回填student │
                          └──────────────┘
```

---

## 5. 聚合分析引擎

### 5.1 职责

将原始 `emotion_record` 按 `(student_id, date, period_id)` 维度聚合为统计指标。

### 5.2 聚合逻辑

```
每次新写入 emotion_record 后，触发增量聚合：

输入: student_id, date, period_id
输出: UPSERT emotion_aggregation

计算指标:
  sample_count     = COUNT(*)

  ratio_happy      = COUNT(dominant_emotion='happy') / sample_count
  ratio_sad        = COUNT(dominant_emotion='sad') / sample_count
  ratio_angry      = COUNT(dominant_emotion='angry') / sample_count
  ratio_surprise   = COUNT(dominant_emotion='surprise') / sample_count
  ratio_fear       = COUNT(dominant_emotion='fear') / sample_count
  ratio_disgust    = COUNT(dominant_emotion='disgust') / sample_count
  ratio_neutral    = COUNT(dominant_emotion='neutral') / sample_count

  positive_ratio   = (ratio_happy + ratio_surprise)
  negative_ratio   = (ratio_sad + ratio_angry + ratio_fear + ratio_disgust)
  engagement_score = positive_ratio × 60 + (1 - negative_ratio) × 40
```

### 5.3 调度方式

```
@EventListener
@Async
public void onImageProcessed(ImageProcessedEvent event) {
    // 增量聚合：只重新计算该学生该时段
    aggregate(event.getStudentId(), event.getDate(), event.getPeriodId());
}
```

SQL 实现:
```sql
INSERT INTO emotion_aggregation (student_id, class_id, date, period_id,
    sample_count, ratio_happy, ratio_sad, ratio_angry, ...,
    positive_ratio, negative_ratio, engagement_score, updated_at)
SELECT
    f.student_id, ci.class_id, ci.capture_time::DATE, ci.period_id,
    COUNT(*) as sample_count,
    COUNT(*) FILTER (WHERE er.dominant_emotion = 'happy')::REAL / COUNT(*) as ratio_happy,
    ...
FROM face_detect_record f
JOIN class_image ci ON ci.id = f.class_image_id
JOIN emotion_record er ON er.face_detect_id = f.id
WHERE f.student_id = ? AND ci.capture_time::DATE = ? AND ci.period_id = ?
GROUP BY f.student_id, ci.class_id, ci.capture_time::DATE, ci.period_id
ON CONFLICT (student_id, date, period_id) DO UPDATE SET
    sample_count = EXCLUDED.sample_count,
    ratio_happy = EXCLUDED.ratio_happy,
    ...,
    updated_at = NOW();
```

---

## 6. 预警引擎

### 6.1 规则语法

| 字段 | 说明 |
|------|------|
| metric | negative_ratio / positive_ratio / engagement_score / absence_count |
| operator | > / >= / < / <= / == |
| threshold | 阈值 (0-1 或 0-100) |
| duration_min | 持续分钟数（null=单次触发） |

### 6.2 触发方式

1. **实时触发** — 聚合更新完成后，对该学生的所有规则逐条评估
2. **定时补偿** — `@Scheduled(fixedRate = 300000)` 每5分钟全量扫描

### 6.3 预警去重

同学生 + 同规则 + `acknowledged=false` 时，不再重复生成 alert_log。

### 6.4 评估流程

```
┌───────────────────────────────────────────────┐
│ AlertEvaluator.evaluate()                      │
│                                                │
│ for each alert_rule WHERE enabled=true:        │
│   for each scope_target (global/grade/class):  │
│     for each student in scope:                 │
│       agg = getAggregation(student, now)       │
│       value = extractMetric(agg, rule.metric)  │
│       triggered = compare(value, rule)         │
│                                                │
│       if triggered AND !alreadyAlerted(student, rule):
│         create alert_log                       │
│         publish AlertTriggeredEvent            │
│         → WebSocket 推送                       │
└───────────────────────────────────────────────┘
```

---

## 7. 看板/报表 API 服务

### 7.1 校级大盘

```
GET /api/v1/school/overview?grade_id=&period=

数据组装:
  1. 查 grade + class 层级
  2. 查 emotion_aggregation 按 grade/class 聚合
  3. 计算全年级 KPI
  4. 查 alert_log 统计异常率排行
  5. 缓存 Redis 5min
```

### 7.2 班级看板

```
GET /api/v1/classes/{id}/dashboard?date=&period_label=

数据组装:
  1. 查该班该时段 emotion_aggregation
  2. 计算 KPI 卡片（快乐率/中性率/异常率/参与度）
  3. 查 emotion_record 构建时间线（每分钟采样）
  4. 查 face_detect_record 构建学生表格
  5. 缺席学生标记
  6. 缓存 Redis 1min
```

### 7.3 座位热力图

```
GET /api/v1/classes/{id}/heatmap?date=&period_label=

数据组装:
  1. 查 class_image + face_detect_record
  2. 按座位区域（从 class_image 位置推断）聚合
  3. 构建 7×N 座位矩阵
  4. 低参与度连续检测
```

### 7.4 学生个人档案

```
GET /api/v1/students/{id}/emotion-timeline?date=&period=

数据组装:
  1. 查 emotion_aggregation 历史
  2. 查 alert_log 异常事件
  3. 查 intervention_log 干预记录
  4. 缓存 Redis 1min
```

### 7.5 缓存策略

| 端点 | TTL | Key 格式 |
|------|-----|----------|
| school/overview | 5min | `school:overview:{grade_id}` |
| classes/{id}/dashboard | 1min | `class:{id}:dashboard:{date}:{period}` |
| classes/{id}/heatmap | 不缓存 | — |
| students/{id}/timeline | 1min | `student:{id}:timeline:{date}` |
| face-clusters | 不缓存 | — |

---

## 8. WebSocket 通知

### 8.1 连接端点

| 路径 | 用途 | 推送内容 |
|------|------|----------|
| `/ws/class/{classId}/emotion` | 班级实时情绪 | `WsEmotionUpdate` |
| `/ws/alerts` | 预警通知 | `WsAlert` |

### 8.2 消息格式

```json
// 班级情绪更新
{
  "type": "emotion_update",
  "class_id": 1,
  "timestamp": "2026-05-27T10:15:00+08:00",
  "updates": [
    {
      "student_id": 1,
      "dominant_emotion": "happy",
      "dominant_confidence": 0.85,
      "engagement": 82
    }
  ]
}

// 预警推送
{
  "type": "alert",
  "alert_id": 42,
  "student_name": "王五",
  "class_name": "初一3班",
  "message": "连续3节悲伤情绪",
  "severity": "high",
  "timestamp": "2026-05-27T10:15:00+08:00"
}
```

### 8.3 推送触发时机

- 聚合更新完成后 → 推送班级情绪更新
- 预警触发后 → 推送预警通知

---

## 9. 历史数据导入

### 9.1 导入流程

```java
ImageImportService.importDateDir("data/2026-0521") {
  1. 扫描 data/{dateDir}/ 下所有 *.jpg
  2. glob 匹配 "早读-到校/*.jpg", "第1节/*.jpg", ...
  3. 对每张图片:
     a. 从文件名解析 capture_time
     b. 从目录名映射 period_label
     c. 创建 ClassImage(status=PENDING)
     d. 推入 Redis Stream（复用实时处理管线）
  4. 返回 { total, imported, failed }
}
```

### 9.2 现有数据量

| 日期 | 图片数 |
|------|--------|
| 2026-05-21(周四) | 704 |
| 2026-05-22(周五) | 670 |
| 2026-05-25(周一) | 660 |
| 2026-05-26(周二) | 659 |
| **总计** | **2,693** |

---

## 10. 底库按班级维护

### 10.1 班级 → VisionMind 人脸库映射

`class` 表已有 `vm_lib_id` 字段，存储 VisionMind FaceLibrary 的 ID。

### 10.2 初始化流程

```
创建班级 → ClassService.create():
  1. INSERT INTO class
  2. VM POST /api/v1/face/libraries     // 已在 FaceLibraryController 中实现
     Body: { name: "初一3班-人脸库", description: "学生人脸底库" }
  3. 回填 class.vm_lib_id
```

### 10.3 人脸搜索限定班级

人脸搜索时通过 `extra` 字段传递班级信息，或在 VisionMind 端按 library_id 限定搜索范围。目前 ExternalFaceController 的 search 方法使用默认库（DEFAULT_LIBRARY），需在集成时确认是否支持按库搜索。

### 10.4 人脸注册关联

注册人脸时通过 `extra` 字段存储业务信息：
```json
"extra": "{\"student_id\":\"42\",\"class_id\":\"1\",\"name\":\"张三\"}"
```

---

## 11. 新增数据库表

### 11.1 face_cluster（人脸聚类）

```sql
CREATE TABLE face_cluster (
    id              BIGSERIAL PRIMARY KEY,
    class_id        BIGINT NOT NULL REFERENCES class(id),
    cluster_key     VARCHAR(64) NOT NULL,           -- 聚类唯一标识
    face_tokens     JSONB NOT NULL,                 -- VM face_token 列表
    sample_count    INT NOT NULL DEFAULT 0,
    first_seen_at   TIMESTAMPTZ,
    last_seen_at    TIMESTAMPTZ,
    centroid        REAL[],                         -- 聚类质心向量
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
      -- pending: 待人工标注
      -- annotated: 已标注并注册底库
      -- merged: 已合并到已有学生
    annotated_by    BIGINT REFERENCES sys_user(id),
    annotated_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_fc_class_status ON face_cluster(class_id, status);
```

### 11.2 class_period（课时段字典 — 已有 DDL，补充说明）

`class_period` 表已在 `mingmou-ddl.sql` 中定义，含 12 个标准课时段的起止时间和 period_key 映射。

---

## 12. 新增文件结构

```
src/main/java/com/school/emotion/
├── service/
│   ├── EmotionAggregationService.java     # 聚合分析引擎
│   ├── AlertEngine.java                   # 预警评估引擎
│   ├── DashboardService.java              # 看板数据组装
│   ├── ReportService.java                 # 报表生成
│   ├── WebSocketPushService.java          # WebSocket 推送
│   ├── ImageImportService.java            # 历史数据导入
│   └── ai/
│       └── FaceClusteringService.java     # 人脸聚类引擎
├── service/analysis/
│   ├── EngagementCalculator.java          # 参与度计算
│   └── EmotionHealthCalculator.java       # 情绪健康度计算
├── controller/
│   ├── SchoolController.java              # 校级大盘 API
│   ├── ClassController.java               # 班级看板 API
│   ├── StudentController.java             # 学生档案 API
│   ├── AlertController.java               # 预警管理 API
│   ├── InterventionController.java        # 干预记录 API
│   └── FaceClusterController.java         # 人脸聚类标注 API
├── event/
│   ├── ImageProcessedEvent.java           # 图片处理完成事件
│   ├── AggregationUpdatedEvent.java       # 聚合更新事件
│   └── AlertTriggeredEvent.java           # 预警触发事件
├── listener/
│   ├── AggregationEventListener.java      # 聚合事件监听器
│   └── AlertEventListener.java            # 预警->WebSocket 监听器
├── model/
│   ├── entity/
│   │   └── FaceCluster.java               # 人脸聚类实体
│   └── dto/
│       ├── SchoolOverviewData.java        # 校级大盘 DTO
│       ├── ClassDashboardData.java        # 班级看板 DTO
│       ├── StudentProfileData.java        # 学生档案 DTO
│       ├── SeatHeatmapData.java           # 热力图 DTO
│       ├── FaceClusterVO.java             # 聚类标注展示 VO
│       └── AnnotateRequest.java           # 标注请求 DTO
├── config/
│   └── WebSocketConfig.java               # STOMP/WebSocket 配置
└── repository/
    ├── EmotionAggregationRepository.java
    ├── AlertRuleRepository.java
    ├── AlertLogRepository.java
    ├── InterventionLogRepository.java
    └── FaceClusterRepository.java
```

---

## 13. 技术决策总结

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 图片传输 | Base64 (VM 要求) | 引擎接口定义 |
| 认证方式 | JWT Token | VM 安全配置 |
| 异步队列 | Redis Stream | 持久化 + 消费者组 |
| 向量存储 | VM 内部管理(Qdrant) | 不额外引入 |
| 聚类算法 | DBSCAN | 无需预设类别数 |
| 聚合触发 | 事件驱动 + 定时补偿 | 实时 + 容错 |
| 预警评估 | 实时 + 5min 全量扫描 | 防止漏报 |
| 看板缓存 | Redis TTL | 高并发降级 |
| WS 协议 | STOMP over WebSocket | 与 Socket.IO 兼容 |
| 历史导入 | 复用已有 Stream 管线 | 零额外基础设施 |
| 冲突处理 | UPSERT | 幂等聚合 |
| VM 调用重试 | Resilience4j (3次, 指数退避) | 已配置 |
| VM 熔断 | Resilience4j (50%失败率, 10min) | 已配置 |
