# 完整数据处理管线设计方案

> 明眸学生情绪感知平台 — 2026-05-29

---

## 1. 概述

### 1.1 目标

基于 `data/` 目录下按学校/年级/班级/时间组织的监控图像，运行完整的数据处理管线：**图片注册 → 人脸检测 → 人脸抠图 → 人脸库注册 + 表情识别 → 聚类分析 → 多维统计**。

### 1.2 方案选择

采用 **纯 Python 管线**（方案A），独立于 Java Spring Boot 后端运行。原因：

- VisionMind face_server C++ gRPC 服务在持续 ~200 次调用后性能退化（请求挂起），Python 可自动检测并重启容器
- 80px 最小人脸、置信度阈值等参数可精确控制
- 完全的断点续传和进度追踪
- 后续可验证通过后迁移回 Java 后端

### 1.3 已知约束

- VisionMind `/v1/face/detect` 外部端点不支持 `minFaceSize` 参数（当前硬编码为 160px），80px 需求需 C++ 引擎侧修改。本方案使用 REST 端点的默认参数，但通过降低置信度阈值（0.3）补偿小脸漏检
- face_server 约 200 次调用后挂起，通过定期 `docker restart` 缓解

---

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                   Python Pipeline Controller                      │
│                                                                   │
│  Phase 1      Phase 2        Phase 3      Phase 4        Phase 5 │
│  ┌────────┐  ┌────────┐   ┌────────┐  ┌────────┐    ┌─────────┐ │
│  │数据注册 │─→│人脸检测 │─→│人脸抠图 │─→│人脸注册 │──→│聚类分析  │ │
│  │        │  │        │   │        │  │+表情识别│   │+统计报表 │ │
│  └────────┘  └────────┘   └────────┘  └────────┘    └─────────┘ │
│       │           │            │           │              │       │
│       v           v            v           v              v       │
│  ┌────────┐  ┌────────┐   ┌────────┐  ┌────────┐    ┌─────────┐ │
│  │Postgres│  │Postgres│   │ 本地   │  │Vision  │    │ Qdrant  │ │
│  │        │  │        │   │ 磁盘   │  │Mind    │    │ +Postgres│
│  └────────┘  └────────┘   └────────┘  │faceDB  │    └─────────┘ │
│                                        └────────┘               │
└─────────────────────────────────────────────────────────────────┘
         ↓ 进度追踪                                     ↑ 失败重试
    progress_tracking 表                       每200次 restart容器
```

---

## 3. 数据模型变更

### 3.1 `progress_tracking`（新建）

```sql
CREATE TABLE progress_tracking (
    id           SERIAL PRIMARY KEY,
    phase        VARCHAR(50) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'pending',
    total        INT DEFAULT 0,
    processed    INT DEFAULT 0,
    failed       INT DEFAULT 0,
    last_id      BIGINT,
    skip_reason  TEXT,
    error_msg    TEXT,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ DEFAULT NOW()
);
```

### 3.2 `class_image` 扩展

```sql
ALTER TABLE class_image ADD COLUMN is_face_detected BOOLEAN DEFAULT FALSE;
ALTER TABLE class_image ADD COLUMN face_detected_at TIMESTAMPTZ;
```

### 3.3 `face_record` 扩展

```sql
ALTER TABLE face_record ADD COLUMN cropped_image_url TEXT;
ALTER TABLE face_record ADD COLUMN is_registered_to_lib BOOLEAN DEFAULT FALSE;
ALTER TABLE face_record ADD COLUMN registered_at TIMESTAMPTZ;
ALTER TABLE face_record ADD COLUMN lib_face_id VARCHAR(64);
ALTER TABLE face_record ADD COLUMN lib_register_status VARCHAR(20) DEFAULT 'pending';
```

### 3.4 `face_cluster` 增强

```sql
ALTER TABLE face_cluster ADD COLUMN avg_similarity REAL;
ALTER TABLE face_cluster ADD COLUMN class_id BIGINT;
```

### 3.5 `emotion_aggregation`（补充学生聚合）

已有表结构基本满足，需确保 `student_id` 被正确填充。

---

## 4. Phase 1: 数据注册

### 4.1 输入

`data/{school}/{class}/{YYYY-MMDD}/{period}/*.jpg`

### 4.2 处理逻辑

1. 遍历 `data/` 目录，解析学校/班级/日期/时段
2. 解析文件名 `YYYYMMDDHHmmss_XXXX.jpg` 提取 `capture_time`
3. 写入 `class_image` 表，status = `PENDING`
4. 记录 `grade` / `class` 表（自动创建）

### 4.3 清空策略

- TRUNCATE 所有业务表（class_image, face_record, emotion_record, face_cluster, emotion_aggregation）
- 保留 grade, class 表（重新创建）
- 清空 Redis `image:ingest` stream
- 重置 `progress_tracking`

### 4.4 断点续传

每处理 50 张更新 `progress_tracking(last_id, processed)`。

---

## 5. Phase 2: 人脸检测

### 5.1 调用

```python
POST /v1/face/detect
{ "image_base64": "<cropped_face>" }

→ 返回 { "faces": [{"bbox": [x,y,w,h], "confidence": 0.xx}] }
```

### 5.2 参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 置信度阈值 | 0.3（降低以补偿80px小脸漏检） | 可配置 |
| 选取策略 | 最高置信度人脸 | 每张图片选一张最佳人脸 |
| 跳过条件 | 无人脸或无人脸达阈值 | status=COMPLETED |

### 5.3 引擎退化处理

```python
def call_with_resilience(image_b64, call_count):
    if call_count > 0 and call_count % 200 == 0:
        docker_restart("docker-face-1-1")
        time.sleep(5)
    return requests.post("/v1/face/detect", ...)
```

### 5.4 结果写入

- 检测到人脸 → 写入 `face_record`，`class_image.status = 'COMPLETED'`
- 未检测到人脸 → `class_image.status = 'COMPLETED'`（跳过）

---

## 6. Phase 3: 人脸抠图

### 6.1 处理

```python
img = cv2.imread(original_image_path)
x, y, w, h = bbox
# 30% 扩边
mx, my = int(w * 0.3), int(h * 0.3)
x1, y1 = max(0, x - mx), max(0, y - my)
x2, y2 = min(w+mx, img_w), min(h+my, img_h)
crop = img[y1:y2, x1:x2]
```

### 6.2 存储

```
images/cropped/{school}/{class}/{date}/{period}/face_{fr_id}.jpg
```

### 6.3 关联

`face_record.cropped_image_url` 指向裁剪图路径
`face_record.class_image_id` → `class_image.image_url`（原图）

---

## 7. Phase 4: 人脸库注册 + 表情识别

### 7.1 人脸库注册

```python
POST /v1/facedb/register
{
    "id": "face_{school_id}_{class_id}_{fr_id}",
    "name": "face_{school_id}_{class_id}_{fr_id}",
    "image": "data:image/jpeg;base64,..."
}
```

### 7.2 表情识别

```python
POST /v1/face/attribute
{
    "image_base64": "<cropped_face_b64>",
    "include": ["expression"]
}

→ { "attributes": [{"expression": {"label": "happy", "probability": 0.87}}] }
```

### 7.3 DB 写入

- `face_record.is_registered_to_lib = TRUE`
- `face_record.lib_face_id = "face_{school_id}_{class_id}_{fr_id}"`
- `face_record.lib_register_status = 'registered' | 'failed'`
- `face_record.status = 'IDENTIFIED'`（有表情）| `'UNIDENTIFIED'`（无表情）
- `emotion_record`（dominant_emotion, dominant_confidence）

### 7.4 错误处理

- 注册失败（"未检测到人脸"）→ `lib_register_status = 'failed'`，继续处理
- 表情分析失败 → `status = 'UNIDENTIFIED'`，继续处理

---

## 8. Phase 5: 聚类分析

### 8.1 方案

利用 Qdrant 中已存储的人脸特征向量进行相似度聚类。

```python
for each face registered in faceDB:
    # Qdrant 相似度搜索
    similar = qdrant.search(
        collection="face_features",
        vector=face_feature,
        limit=20,
        score_threshold=0.7
    )
    # 分组: 相互相似度 > 0.7 且数量 >= 3 的归为一簇
    groups = cluster_by_similarity(all_faces, threshold=0.7, min_cluster=3)
    
    for group in groups:
        create_or_update_face_cluster(
            cluster_key = f"cluster_{hash}",
            face_ids = [f.id for f in group],
            sample_count = len(group),
            avg_similarity = avg(group.similarity)
        )
```

### 8.2 聚类报告

```
总检测人脸数: N
成功注册人脸库: N (X%)
聚类结果:
  - 总簇数: N
  - >= 3 张的簇: N
  - 最大簇: N 张 (相似度 X.XX)
  - 平均簇大小: X.X
  - 未聚类人脸(孤点): N
跨时段重复检测率: X%
```

---

## 9. Phase 6: 多维表情统计

### 9.1 聚合维度

```
        学校级
           ↓
        年级级
           ↓
        班级级
        ↙     ↘
     日期维度  时段维度
        ↓       ↓
   情绪分布   参与度曲线
```

### 9.2 KPI 计算

| KPI | 公式 |
|-----|------|
| 情绪健康度 | `(happy + surprise) / total * 100` |
| 消极情绪率 | `(sad + angry + fear + disgust) / total * 100` |
| 课堂参与度 | `positive_ratio * 60 + (1 - absence_ratio) * 40` |
| 关注度 | `negative_ratio > 阈值` 的学生数 |

### 9.3 报表输出

- `emotion_aggregation` 按 `(student_id, date, period_id)` 聚合
- 交叉分析：班级×日期×时段 × 情绪类型
- 趋势分析：按时间序列展示各情绪比例变化
- 簇分析：同簇人脸的时间/地点分布

---

## 10. 进度追踪与断点续传

### 10.1 progress_tracking 记录格式

每条记录代表一个处理阶段的运行实例：

```json
{
  "phase": "detection",
  "status": "running",
  "total": 2704,
  "processed": 1250,
  "failed": 3,
  "last_id": 4321,
  "skip_reason": "engine_degradation_restart",
  "started_at": "2026-05-29T10:00:00Z"
}
```

### 10.2 重启恢复

1. 启动时查询 `progress_tracking` 各 phase 的状态
2. `completed` → 跳过
3. `running` / `failed` → 从 `last_id` 继续
4. `pending` → 从头开始

---

## 11. 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Python 3 | ≥3.10 | 运行环境 |
| psycopg2-binary | — | PostgreSQL 连接 |
| redis | — | Redis 操作 |
| requests | — | HTTP 调用 VisionMind API |
| opencv-python (cv2) | — | 图片读取、裁剪 |
| numpy | — | 图片数组操作 |

---

## 12. 管线调用

```bash
# 清空数据 + 全量运行
python3 pipeline.py --clean --all

# 从指定 Phase 开始
python3 pipeline.py --phase registration
python3 pipeline.py --phase detection
python3 pipeline.py --phase cropping
python3 pipeline.py --phase library_reg
python3 pipeline.py --phase clustering
python3 pipeline.py --phase statistics

# 从断点继续
python3 pipeline.py --resume
```
