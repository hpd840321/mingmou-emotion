# 学生表情识别与身心健康管理平台 — PRD

> 产品版本: v1.0
> 日期: 2026-05-27
> 技术栈: Spring Boot 4.0 + Spring AI 1.x + PostgreSQL 16
> 状态: Draft

---

## 目录

1. [产品概述](#1-产品概述)
2. [用户角色](#2-用户角色)
3. [功能需求](#3-功能需求)
4. [系统架构](#4-系统架构)
5. [数据模型](#5-数据模型)
6. [API 设计](#6-api-设计)
7. [AI 集成方案](#7-ai-集成方案)
8. [数据分析与报表](#8-数据分析与报表)
9. [非功能需求](#9-非功能需求)
10. [数据安全与隐私](#10-数据安全与隐私)
11. [实施路线图](#11-实施路线图)

---

## 1. 产品概述

### 1.1 产品背景

学校已部署教室监控系统，以每分钟1张的频率采集课堂图像并按课时段归档。现有数据覆盖4个教学日（2026-05-21、22、25、26），已按「早读→第1~8节→课间操→午休→课外活动」等12个时段组织。

在此基础上，需构建一个全校级的学生表情识别与情绪管理平台，接入第三方图片源，通过AI引擎识别学生面部表情，实现从**个人→班级→年级/校级**的三级情绪态势感知。

### 1.2 产品目标

- **V1.0 核心目标**: 建立表情识别数据管道，实现学生个人情绪档案 + 班级课堂情绪看板
- **V1.5 扩展目标**: 身心健康预警（异常情绪检测、长期趋势分析）
- **V2.0 愿景**: 情绪数据驱动教学改进、个性化心理干预

### 1.3 关键约束

| 约束项 | 说明 |
|---|---|
| 图片来源 | 第三方组件按班级推送（含图片 + 班级ID + 时间戳），不直接管理摄像头 |
| AI引擎 | 人脸识别和表情识别由独立第三方API提供，平台负责集成编排 |
| 技术栈 | Spring Boot 4.0 + Spring AI 1.x + PostgreSQL 16 |
| 数据分级 | 个人 → 班级 → 年级 → 校级，严格控制数据可见范围 |

---

## 2. 用户角色

| 角色 | 权限范围 | 典型用户 |
|---|---|---|
| **系统管理员** | 全校配置、用户管理、AI引擎管理 | IT管理员 |
| **校级管理者** | 全校/年级报表，重点关注学生 | 校长、德育主任 |
| **年级组长** | 本年级各班对比、年级趋势 | 年级主任 |
| **班主任** | 本班学生详情、课堂情绪看板 | 班主任老师 |
| **心理辅导老师** | 重点关注学生名单、干预记录 | 心理老师 |
| **学生/家长** | 个人情绪报告（脱敏后） | 学生、家长 |

---

## 3. 功能需求

### 3.1 图片接入管理 (P0)

| 功能 | 描述 |
|---|---|
| `F-01` 图片接收API | 接收第三方推送的图片流，含班级ID、拍摄时间、课时段标签 |
| `F-02` 图片队列缓冲 | 异步队列处理，防止高并发时AI引擎过载 |
| `F-03` 接入监控 | 查看接入状态、失败重试、延迟统计 |
| `F-04` 历史图片导入 | 支持批量导入已有归档图片（如现有4天数据） |

### 3.2 人脸识别处理 (P0)

| 功能 | 描述 |
|---|---|
| `F-05` 人脸检测 | 调用外部API检测图片中所有人脸及位置 |
| `F-06` 人脸注册/识别 | 首次出现的人脸注册为新学生，之后自动匹配已有身份 |
| `F-07` 人脸库管理 | 学生人脸底库维护（增删改查） |
| `F-08` 多人脸处理 | 同一图片中多人脸的独立识别与跟踪 |

### 3.3 表情识别与分析 (P0)

| 功能 | 描述 |
|---|---|
| `F-09` 表情识别 | 调用外部API识别7种基础表情：快乐、悲伤、愤怒、惊讶、恐惧、厌恶、中性 |
| `F-10` 表情置信度 | 记录每种表情的概率分布（而非仅最高分） |
| `F-11` 表情时间序列 | 以课堂为粒度，构建学生表情变化曲线 |
| `F-12` 缺席检测 | 若某学生持续多张图片未识别到，记录异常 |

### 3.4 个人维度分析 (P0)

| 功能 | 描述 |
|---|---|
| `F-13` 学生情绪档案 | 每位学生的表情历史趋势、日/周/月报表 |
| `F-14` 课堂参与度 | 基于正面表情频率 + 人脸朝向估算课堂参与指标 |
| `F-15` 异常情绪预警 | 连续N张/连续N天出现负面情绪（悲伤/愤怒/恐惧）时触发 |
| `F-16` 重点关注名单 | 班主任/心理老师可标记重点关注学生 |

### 3.5 班级维度分析 (P1)

| 功能 | 描述 |
|---|---|
| `F-17` 班级情绪看板 | 当前课堂/今日全班情绪分布实时视图 |
| `F-18` 班级趋势对比 | 同一班级不同课时段、日期的情绪曲线对比 |
| `F-19` 班级活跃度热力图 | 以座位/区域为维度的课堂参与度热力图 |
| `F-20` 班级报告 | 自动生成日报/周报/月报 |

### 3.6 校级维度分析 (P1)

| 功能 | 描述 |
|---|---|
| `F-21` 年级/全校大盘 | 各年级/班级情绪健康度横向对比 |
| `F-22` 重点关注预警 | 跨班级的异常情绪学生汇总 |
| `F-23` 趋势报告 | 学期情绪趋势分析 |

### 3.7 身心健康管理 (P2)

| 功能 | 描述 |
|---|---|
| `F-24` 干预记录 | 心理老师记录针对特定学生的干预动作及效果 |
| `F-25` 预警规则引擎 | 自定义预警阈值（连续负面表情次数/时长等） |
| `F-26` 通知推送 | 异常预警推送至班主任/心理老师 |

---

## 4. 系统架构

### 4.1 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                    WEB 展示层 (Thymeleaf/Vue)              │
│  校级大盘 │ 年级报表 │ 班级看板 │ 个人档案 │ 系统管理     │
└──────────────────────────┬──────────────────────────────┘
                           │ REST API
┌──────────────────────────▼──────────────────────────────┐
│                    业务服务层 (Spring Boot 4.0)            │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ 图片接入  │ │ 人脸服务  │ │ 表情服务  │ │ 分析服务  │   │
│  │ Service   │ │ Service   │ │ Service   │ │ Service   │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ 预警引擎  │ │ 报表引擎  │ │ 通知服务  │ │ 用户管理  │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                   AI 集成层 (Spring AI)                    │
│  ┌────────────────┐  ┌────────────────┐                  │
│  │ 人脸识别API     │  │ 表情识别API     │                  │
│  │ (FaceNet/第三方) │  │ (ResNet/第三方)  │                  │
│  └────────────────┘  └────────────────┘                  │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                   数据层 (PostgreSQL 16)                   │
│  学生库 │ 班级库 │ 人脸记录 │ 表情记录 │ 聚合表 │ 预警规则 │
└─────────────────────────────────────────────────────────┘
```

### 4.2 核心处理流程

```
第三方推送图片 → [图片接入Controller] → 消息队列(内存/MQ)
                                           ↓
                [人脸识别Service] ← Spring AI → 人脸识别API
                      ↓ (face_id + bbox)
                [表情识别Service] ← Spring AI → 表情识别API
                      ↓ (emotion_label + confidence)
                [分析引擎Service]
                      ├→ 写入表情记录表
                      ├→ 更新学生实时情绪状态
                      ├→ 检查预警规则 → 触发通知
                      └→ 更新聚合数据
```

### 4.3 处理模式

```
图片接入 → 异步队列 → 人脸批量识别 → 表情批量识别 → 持久化 → 聚合更新
                                          ↕
                                   失败重试队列 (最多3次)
```

- 采用事件驱动架构，图片接入后立即返回ACK
- AI识别通过 Spring AI 抽象层调用，切换AI后端不改业务代码
- 识别结果异步写入，支撑高吞吐

---

## 5. 数据模型

### 5.1 核心实体关系

```
grade (年级) 1──N class (班级) 1──N student (学生)
class 1──N class_image (课堂图片)
student 1──N face_record (人脸记录)
face_record 1──1 emotion_record (表情记录)
student 1──N emotion_aggregation (聚合数据)
alert_rule N──1 grade/class (预警规则)
intervention_log N──1 student (干预记录)
```

### 5.2 关键表结构

#### grade（年级）
```sql
CREATE TABLE grade (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,       -- e.g. "初一", "初二"
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

#### class（班级）
```sql
CREATE TABLE class (
    id          BIGSERIAL PRIMARY KEY,
    grade_id    BIGINT       NOT NULL REFERENCES grade(id),
    name        VARCHAR(50)  NOT NULL,       -- e.g. "初一班", "初二(3)班"
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

#### student（学生）
```sql
CREATE TABLE student (
    id            BIGSERIAL PRIMARY KEY,
    class_id      BIGINT      NOT NULL REFERENCES class(id),
    student_no    VARCHAR(20) NOT NULL UNIQUE,  -- 学号
    name          VARCHAR(50) NOT NULL,
    face_image_id VARCHAR(64),                  -- 注册人脸ID
    status        VARCHAR(20) NOT NULL DEFAULT 'active', -- active / transferred / graduated
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

#### class_image（课堂图片记录）
```sql
CREATE TABLE class_image (
    id              BIGSERIAL PRIMARY KEY,
    class_id        BIGINT       NOT NULL REFERENCES class(id),
    image_url       TEXT         NOT NULL,        -- 图片存储路径/URL
    capture_time    TIMESTAMPTZ  NOT NULL,         -- 拍摄时间
    period_label    VARCHAR(20),                  -- 课时段标签 (第1节/午休等)
    source          VARCHAR(50)  DEFAULT 'third_party',
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
      -- pending → processing → completed / failed
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ci_class_time ON class_image(class_id, capture_time);
```

#### face_record（人脸检测记录）
```sql
CREATE TABLE face_record (
    id              BIGSERIAL PRIMARY KEY,
    class_image_id  BIGINT       NOT NULL REFERENCES class_image(id),
    student_id      BIGINT       REFERENCES student(id),  -- NULL = 未识别
    bbox            JSONB,       -- {x, y, width, height} 人脸框坐标
    face_encoding   JSONB,       -- 人脸特征向量 (float[])
    confidence      REAL,        -- 识别置信度
    status          VARCHAR(20)  NOT NULL DEFAULT 'detected',
      -- detected → identified / unidentified
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fr_image   ON face_record(class_image_id);
CREATE INDEX idx_fr_student ON face_record(student_id);
```

#### emotion_record（表情识别记录）
```sql
CREATE TABLE emotion_record (
    id                BIGSERIAL PRIMARY KEY,
    face_record_id    BIGINT       NOT NULL UNIQUE REFERENCES face_record(id),
    -- 7种基础表情的置信度分布
    emotion_happy     REAL,
    emotion_sad       REAL,
    emotion_angry     REAL,
    emotion_surprise  REAL,
    emotion_fear      REAL,
    emotion_disgust   REAL,
    emotion_neutral   REAL,
    -- 主导表情
    dominant_emotion  VARCHAR(20) NOT NULL,
    dominant_confidence REAL     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_er_dominant ON emotion_record(dominant_emotion);
```

#### emotion_aggregation（聚合数据 - 按学生+课时段）
```sql
CREATE TABLE emotion_aggregation (
    id                BIGSERIAL PRIMARY KEY,
    student_id        BIGINT       NOT NULL REFERENCES student(id),
    class_id          BIGINT       NOT NULL REFERENCES class(id),
    date              DATE         NOT NULL,
    period_label      VARCHAR(20)  NOT NULL,     -- 课时段
    -- 该时段内表情统计（与 emotion_record 字段对应）
    sample_count      INT          NOT NULL DEFAULT 0,
    avg_emotion_happy     REAL,
    avg_emotion_sad       REAL,
    avg_emotion_angry     REAL,
    avg_emotion_surprise  REAL,
    avg_emotion_fear      REAL,
    avg_emotion_disgust   REAL,
    avg_emotion_neutral   REAL,
    engagement_score  REAL,                      -- 参与度得分 (0-100)
    -- 异常标记
    anomaly_flag      BOOLEAN      NOT NULL DEFAULT false,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_ea_unique
    ON emotion_aggregation(student_id, date, period_label);
```

#### alert_rule / alert_log / intervention_log（预警与干预）
```sql
CREATE TABLE alert_rule (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    scope           VARCHAR(20)  NOT NULL,       -- global / grade / class
    scope_id        BIGINT,                      -- 对应的grade_id/class_id
    metric          VARCHAR(50)  NOT NULL,       -- negative_emotion_ratio / absence / etc.
    operator        VARCHAR(10)  NOT NULL,       -- > / >= / < / <= / ==
    threshold       REAL         NOT NULL,
    duration_min    INT,                         -- 持续分钟数 (可选)
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE alert_log (
    id              BIGSERIAL PRIMARY KEY,
    alert_rule_id   BIGINT NOT NULL REFERENCES alert_rule(id),
    student_id      BIGINT REFERENCES student(id),
    class_id        BIGINT REFERENCES class(id),
    trigger_value   REAL,
    message         TEXT,
    acknowledged    BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE intervention_log (
    id              BIGSERIAL PRIMARY KEY,
    student_id      BIGINT       NOT NULL REFERENCES student(id),
    teacher_id      BIGINT,                      -- 干预人（教师用户ID）
    action_type     VARCHAR(50)  NOT NULL,        -- talk / counseling / notify_parent / etc.
    description     TEXT,
    effect          VARCHAR(500),                 -- 干预效果记录
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
```

---

## 6. API 设计

### 6.1 第三方图片接入 API

```
POST /api/v1/images/ingest
Content-Type: multipart/form-data

请求参数:
  image:         File (JPEG, max 10MB)
  class_id:      Long      — 班级ID
  capture_time:  String    — ISO 8601 拍摄时间
  period_label:  String    — 课时段标签 (可选)

响应:
  {
    "code": 0,
    "message": "accepted",
    "data": { "image_id": 12345, "queue_position": 42 }
  }
```

### 6.2 核心业务 API

```
# 个人维度
GET  /api/v1/students/{id}/emotion-timeline?date=2026-05-26&period=第1节
GET  /api/v1/students/{id}/emotion-report?start=2026-05-01&end=2026-05-31
GET  /api/v1/students/{id}/alerts

# 班级维度
GET  /api/v1/classes/{id}/dashboard?date=2026-05-26
GET  /api/v1/classes/{id}/emotion-trend?start=...&end=...
GET  /api/v1/classes/{id}/heatmap?date=2026-05-26&period=第1节

# 校级维度
GET  /api/v1/school/overview?grade_id=...
GET  /api/v1/school/alerts?status=active
GET  /api/v1/school/report?type=weekly

# 预警管理
POST /api/v1/alert-rules
GET  /api/v1/alert-logs?class_id=...
POST /api/v1/interventions
```

### 6.3 WebSocket 实时推送

```
WS /ws/class/{classId}/emotion      — 班级实时情绪数据推送
WS /ws/alerts                        — 预警事件实时推送
```

---

## 7. AI 集成方案

### 7.1 Spring AI 集成架构

```java
// 统一AI服务接口（Spring AI 抽象层）
public interface FaceRecognitionService {
    List<FaceResult> detectFaces(byte[] imageData);
    String identifyFace(byte[] faceCrop);
    void registerFace(String studentId, byte[] faceImage);
}

public interface EmotionRecognitionService {
    EmotionResult analyzeEmotion(byte[] faceCrop);
}
```

- 具体实现通过 Spring AI 的 `@Service` 注解切换
- 支持配置多AI供应商（主/备切换）
- 失败重试 + 熔断（集成 Resilience4j）

### 7.2 人脸识别流程

```
图片 → 人脸检测API → [人脸框1, 人脸框2, ...]
         ↓
逐个人脸裁剪 → 人脸识别API (1:N比对)
         ↓
    ┌─ 匹配 → student_id
    └─ 不匹配 → 标记unidentified，可手动归并
```

### 7.3 表情识别流程

```
人脸裁剪图 → 表情识别API → {happy: 0.85, sad: 0.02, ...}
                ↓
         dominant_emotion = happy
         dominant_confidence = 0.85
```

### 7.4 降级策略

| 场景 | 策略 |
|---|---|
| AI API 超时 | 重试3次，间隔递增(1s/3s/5s) |
| AI API 不可用 | 熔断10分钟，图片标记为 `processing_failed` |
| 人脸识别失败 | 跳过表情识别，记录 `face_detection_failed` |
| 队列积压 | 动态调整批处理大小，丢弃超过TTL的图片 |

---

## 8. 数据分析与报表

### 8.1 分析维度矩阵

| 维度 \ 粒度 | 实时(当前课堂) | 日 | 周 | 月 | 学期 |
|---|---|---|---|---|---|
| **个人** | 当前表情状态 | 日情绪曲线 | 周趋势 | 月报告 | 学期档案 |
| **班级** | 班级情绪分布 | 班级日报 | 周报 | 月报 | 学期报告 |
| **年级** | — | — | 年级周报 | 年级月报 | 学期报告 |
| **校级** | — | — | 全校周报 | 全校月报 | 学期报告 |

### 8.2 核心指标

| 指标 | 计算方式 | 含义 |
|---|---|---|
| 情绪健康度 | (正面表情占比 / 总样本) × 100 | 数值越高越积极 |
| 课堂参与度 | (正面表情 + 注视前方) / 总样本 × 100 | 学生投入程度 |
| 异常情绪率 | 负面表情样本 / 总样本 × 100 | 需关注比例 |
| 情绪波动指数 | 各时段情绪得分的标准差 | 情绪稳定性 |
| 重点关注增长率 | 本周新增重点关注数 / 上周总数 | 预警趋势 |

### 8.3 报表输出

- **日报**: 自动生成，推送至班主任/年级组长
- **周报**: 含趋势对比、异常汇总
- **月报**: 含年级对比、重点关注学生清单
- **导出格式**: PDF / Excel / 在线查看

---

## 9. 非功能需求

| 需求 | 指标 |
|---|---|
| 图片处理吞吐 | ≥ 60张/分钟（单教室1分钟1张，支持50+教室并发） |
| AI识别延迟 | 单张图片从接入到完成 ≤ 30秒（P99） |
| 查询响应 | 个人/班级看板 ≤ 2秒，校级报表 ≤ 5秒 |
| 可用性 | 核心功能 99.9%（图片接入、情绪识别持续可用） |
| 数据保留 | 原始图片保留30天，分析数据保留3年 |
| 并发用户 | 支持 200 教师同时在线（高峰期） |
| 扩展性 | 通过水平扩展支持 200+ 教室接入 |

---

## 10. 数据安全与隐私

### 10.1 合规要求

- 遵守《个人信息保护法》(PIPL) 关于未成年人数据的规定
- 学生人脸数据属于敏感个人信息，需单独授权
- 数据存储加密（AES-256 静态加密）
- 传输加密（TLS 1.3）

### 10.2 访问控制

- 基于角色的权限控制（RBAC）
- 数据隔离：班主任只能看本班，年级组长只能看本年级
- 操作审计日志：所有数据访问和修改留痕
- 学生/家长只能查看本人报告（脱敏后）

### 10.3 数据脱敏

- 对外报表中学生姓名可用 "张**" 格式脱敏
- 原始人脸图片禁止在非授权页面展示
- 人脸特征向量不可逆向还原为原始图片

---

## 11. 实施路线图

### Phase 1 — 基础管道 (2-3周)

| 任务 | 说明 |
|---|---|
| 项目骨架搭建 | Spring Boot 4.0 项目初始化，PostgreSQL schema |
| 图片接入API | 第三方接收接口 + 异步队列处理 |
| Spring AI 集成 | 对接人脸/表情识别API |
| 基础数据入库 | 人脸识别 + 表情识别结果写入 |
| 现有数据导入 | 将已归档的4天图片导入处理 |

### Phase 2 — 分析展示 (2-3周)

| 任务 | 说明 |
|---|---|
| 学生人脸注册 | 人脸底库管理功能 |
| 个人情绪档案 | 时间线 + 日/周报表 |
| 班级情绪看板 | 实时分布 + 趋势图 |
| 课时段关联分析 | 接入已有时段标签，分析各时段情绪差异 |

### Phase 3 — 预警与扩展 (2周)

| 任务 | 说明 |
|---|---|
| 预警规则引擎 | 可配置的异常检测规则 |
| 干预记录 | 心理老师干预闭环 |
| 通知推送 | WebSocket + 站内通知 |
| 校级大盘 | 年级/全校横向对比 |

### Phase 4 — 优化与上线 (1-2周)

| 任务 | 说明 |
|---|---|
| 性能优化 | 查询优化、缓存策略 |
| 安全审计 | 数据隐私检查、权限测试 |
| 用户培训 | 教师操作手册 |
| 灰度上线 | 先接入1个年级验证 |

---

## 附录

### A. 现有数据映射

当前已有4天数据与平台实体的映射关系：

```
2026-0521/  (周四)  → 班级: 初一班, 日期: 2026-05-21
2026-0522/  (周五)  → 班级: 初一班, 日期: 2026-05-22
2026-0525/  (周一)  → 班级: 初一班, 日期: 2026-05-25
2026-0526/  (周二)  → 班级: 初一班, 日期: 2026-05-26

子目录(课时段) → period_label:
  早读-到校   → arrival
  第1~8节     → period_1 ~ period_8
  课间操      → recess
  午餐-午休   → lunch
  课外活动-放学 → afterclass
```

图片命名解析 → 可提取 capture_time: `YYYY-MM-DD HH:MM:SS`

### B. 技术依赖

| 组件 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 4.0.x | 应用框架 |
| Spring AI | 1.x | AI API 统一抽象 |
| PostgreSQL | 16 | 主数据库 |
| Flyway | 最新 | 数据库迁移 |
| Resilience4j | 最新 | 熔断/重试 |
| Redis | 7.x | 缓存/队列/限流 |

### C. 术语表

| 术语 | 说明 |
|---|---|
| 课时段 | 按学校课表划分的时间区间（第1节/午休/课外活动等） |
| 情绪健康度 | 正面表情占比的量化指标 |
| 重点关注 | 被标记为需要心理关注的学生 |
| 人脸底库 | 已注册的学生人脸特征向量库 |
| 参与度 | 基于表情和面部朝向估算的课堂投入程度 |
