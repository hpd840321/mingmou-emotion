# 前后端实现一致性复盘报告

> 2026-05-29

---

## 1. 页面与 API 对应关系

### SchoolOverview.vue — 校级大盘

| 前端需求 | 后端 API | 状态 |
|---------|----------|:----:|
| KPI 卡片 | `GET /api/v1/school/overview` → `kpis[]` | ✅ 有返回，但值为 0 |
| 年级对比条形图 | `overview.gradeComparison[]` | ⚠️ **空数组**，后端未实现数据填充 |
| 异常排行 Top5 | `overview.alertRanking[]` | ⚠️ **空数组**，未实现 |
| 全校趋势折线图 | `overview.trendData` | ⚠️ 前端用 mock 数据 `[72,75,68,78]`，非真实数据 |

### ClassDashboard.vue — 班级看板

| 前端需求 | 后端 API | 状态 |
|---------|----------|:----:|
| KPI 卡片 | `GET /api/v1/classes/{id}/dashboard` → `kpis[]` | ⚠️ `aggregations` 为 0（无数据） |
| 情绪时间线 | `dashboard.timelineData[]` | ⚠️ 前端要求 `{time, happy, sad, angry, fear}`，后端未返回此格式 |
| 学生表格 | `dashboard.students[]` | ⚠️ 要求 `{name, studentNo, dominantEmotion, happy, engagement}` 等字段，后端未实现 |
| 时段导航 | `?date=&period_label=` 参数 | ✅ 后端支持 |

### SeatHeatmap.vue — 座位热力图

| 前端需求 | 后端 API | 状态 |
|---------|----------|:----:|
| 座位矩阵 | `GET /api/v1/classes/{id}/heatmap` → `{seats, rows, cols}` | ❌ **后端仅返回 `totalImages`**，无座位数据 |
| 分布统计 | `heatmap.distribution[]` | ❌ 同上 |
| 低参与度告警 | `heatmap.lowEngagementAlerts[]` | ❌ 未实现 |

### StudentProfile.vue — 学生画像

| 前端需求 | 后端 API | 状态 |
|---------|----------|:----:|
| 基本信息 | `GET /api/v1/students/{id}/emotion-timeline` | ⚠️ 依赖 `student_id` 关联，当前全部为 NULL |
| 趋势图 | `profile.trendData[]` | ❌ 无数据 |
| 周分布饼图 | `profile.weekDistribution` | ❌ 无数据 |
| 异常时间线 | `profile.alertTimeline[]` | ⚠️ 依赖 alert 规则触发 |

### FaceClusterPage.vue — 人脸聚类

| 前端需求 | 后端 API | 状态 |
|---------|----------|:----:|
| 聚类列表 | `GET /api/v1/face-clusters?classId=&status=pending` | ⚠️ 后端方法要求 `classId`，但 `FaceClusteringServiceV2` 未设置 `classId`（始终为 0） |
| 标注提交 | `POST /api/v1/face-clusters/{id}/annotate` | ⚠️ `registerFace` 传空字节数组（已修复） |
| 合并操作 | `POST /api/v1/face-clusters/{id}/merge` | ✅ 已实现 |

### AdminPage.vue — 系统管理

| 前端需求 | 后端 API | 状态 |
|---------|----------|:----:|
| 年级管理 | 无 | ❌ 无 CRUD 端点 |
| 班级管理 | 无 | ❌ 无 CRUD 端点，`vmLibId` 标记无后端支持 |
| 系统配置 | 无 | ❌ 无配置读写 API |
| 数据导入 | `POST /api/v1/admin/import?dateDir=...` | ✅ 存在 |
| 管线触发 | `POST /api/v1/admin/pipeline/run` | ✅ **已新增但前端无按钮** |

---

## 2. 后端已实现但前端未使用的 API

| 端点 | 用途 | 未使用原因 |
|------|------|-----------|
| `POST /api/v1/admin/pipeline/run` | 触发完整检测管线 | AdminPage 无对应按钮 |
| `GET /api/v1/school-tree` | 学校树结构 | `SchoolTree.vue` 使用但独立路由 |
| `GET /school-tree/student/{id}/raw-emotions` | 原始情绪数据 | 同上 |
| `POST /api/v1/images/ingest` | 单张图片上传 | 前端无上传页面 |
| `WebSocket /ws/**` | 实时推送 | 后端 WebSocketConfig 已配但无实际 STOMP 端点 |

---

## 3. 前端依赖但后端未实现的功能

| 功能 | 前端页面 | 缺失原因 |
|------|---------|---------|
| 年级 CRUD | AdminPage | 后端无对应 Controller |
| 班级 CRUD | AdminPage | 同上 |
| 系统配置读写 | AdminPage | 同上 |
| 座位编排管理 | SeatHeatmap | 无 `seat` 表和后端逻辑 |
| 实时 WebSocket 推送 | ClassDashboard | `WebSocketConfig` 有配置但无 `@MessageMapping` 端点 |
| 学生搜索 | ClassDashboard | `students` 列表为空 |
| 情绪详细维度概率 | StudentProfile | `EmotionRecord` 各维度字段为 null |
| 图片预览 | FaceClusterPage | `sampleImages` 未传入 |
| 时段分布标签 | FaceClusterPage | `periodLabels` 未传入 |

---

## 4. 数据流断裂点

### 4.1 `student_id` 空 — 最严重

```
data/目录 → class_image → face_detection → face_record.student_id = NULL (全部2406条)
                                                                        ↓
                                                           emotion_aggregation 空
                                                                        ↓
                                                SchoolOverview KPI = 0, ClassDashboard 无数据
```

**所有前端页面的图表数据最终依赖 `student_id` 关联**，而该字段从未被自动填充。`FaceLibraryService.annotateCluster()` 是唯一的设置路径，但：
- 需要人工在前端 FaceClusterPage 标注
- 聚类功能在旧版中无效（新版 `FaceClusteringServiceV2` 尚未集成到前端）

### 4.2 聚合数据计算

`EmotionAggregationService` 按 `student_id` 聚合，但无 `student_id` → 聚合结果为 0。
`EmotionStatisticsService` 新增按 `class_id × date` 聚合，但前端未使用这些数据。

---

## 5. 待完善功能清单

| 优先级 | 功能 | 工作量 | 说明 |
|:------:|------|:------:|------|
| P0 | **student_id 自动关联** | 中 | 基于 Qdrant 搜索自动匹配学生人脸 |
| P0 | **聚合数据填充** | 小 | 修改 DashboardService 使用 EmotionStatisticsService 的结果 |
| P1 | **AdminPage 管线触发按钮** | 小 | 加一个按钮调用 `POST /admin/pipeline/run` |
| P1 | **ClassDashboard students 列表** | 中 | 需要按 class+date+period 返回聚合学生数据 |
| P2 | **SeatHeatmap 数据结构** | 大 | 需要新增座位表和编排逻辑 |
| P2 | **WebSocket 实时推送** | 中 | `WebSocketConfig` 已配但未接入业务事件 |
| P2 | **AdminPage 班级/年级管理** | 中 | 需要 CRUD Controller |
| P3 | **情绪维度概率存储** | 小 | `saveResults` 填充各 emotion_* 字段 |
