# 前端页面全面复盘报告

> 日期: 2026-05-31 | 范围: 11 个前端页面 + 对应后端 API

---

## 一、数据流依赖链（所有页面无数据的根因）

```
人脸检测引擎 (InspireFace) → 0 张人脸
    ↓
face_record = 0 新增 (现有 939 条旧数据)
    ↓
emotion_record = 0 新增 (现有 62 条旧数据)
    ↓
emotion_aggregation = 2 条 (旧数据)
    ↓    ↓          ↓
SchoolOverview  ClassDashboard  StudentProfile
  全部 KPI=0     空表格         无数据
```

所有页面的数据最终依赖 `EmotionAggregation`，而该表只有 2 条旧记录。
`EmotionAggregationService.aggregate()` 依赖 `student_id` 关联，但全部 face_record 的 `student_id = NULL`。

---

## 二、各页面缺陷清单

### 1. SchoolOverview.vue — 校级大盘

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 趋势图使用硬编码 Mock 数据 `[72,75,68,78]` | 🐛 数据 | P1 | 后端无 `trendData` 接口，前端写了假数据 |
| `gradeComparison` 始终为空数组 | 🐛 数据 | P2 | `DashboardService` 逻辑正确但 `aggregation` 为空 |
| `alertRanking` 始终为空 | 🐛 数据 | P2 | 同上依赖 aggregation |
| `crossClassAlerts` 始终为空 | 🐛 数据 | P2 | `dto.setCrossClassAlerts(new ArrayList<>())` 硬编码空 |

**后端**: `DashboardService.java` 逻辑正确，无数据是因为 aggregation 为空。

### 2. ClassDashboard.vue — 班级看板

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 学生列表始终为空 | 🐛 数据 | P1 | `studentRepository.findByClazz_Id()` 返回 0（学生已删除） |
| 情绪时间线无数据 | 🐛 数据 | P1 | 后端未实现 `timelineData` 格式，仅返回 aggregation |
| 表格列 `dominantEmotion` 为 null | 🐛 数据 | P2 | 即使有 student，也无 aggregation 匹配 |
| 参与度进度条始终为 0 | 🐛 数据 | P2 | engagement=0 因无 aggregation |
| `searchQuery` 搜索纯本地过滤 | ⚡ 性能 | P3 | 无后端搜索，数据量大时无效 |

**后端**: `ClassController.dashboard()` 返回 `{students, aggregations}` 但不含 `timelineData` 和 `kpis`。前端期望的 `{kpis, timelineData, students}` 三个核心字段，后端只部分提供。

### 3. SeatHeatmap.vue — 座位热力图

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 座位矩阵完全不可用 | 🐛 功能 | P0 | 学生已删除 → seats=[] → 无座位显示 |
| `distribution` 硬编码空数组 | 🐛 数据 | P2 | `data.put("distribution", List.of())` |
| `lowEngagementAlerts` 硬编码空 | 🐛 数据 | P2 | `data.put("lowEngagementAlerts", List.of())` |
| 无座位编排 UI | 🏗️ 缺失 | P3 | 无后端 seat 表，无法配置座位 |
| 实际座椅坐标=`(index/8, index%8)` | 🐛 功能 | P2 | 伪座位排布，非真实教室座位 |

**后端**: `ClassController.heatmap()`: seats 按 `(index/8, index%8)` 算法排布，无真实座位数据。

### 4. StudentProfile.vue — 学生画像

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 学生不存在 → 永不显示 | 🐛 数据 | P0 | student_id=0，路由 `/student/:id` 查无此人 |
| `trendData` 后端未实现 | 🐛 数据 | P1 | 前端期望 `{date, happy, sad}` 格式 |
| `weekDistribution` 后端未实现 | 🐛 数据 | P1 | 前端期望饼图数据 |
| `alertTimeline` 后端未实现 | 🐛 数据 | P2 | 依赖告警规则触发 |
| 标签 `tagType()` 写死逻辑 | 🐛 功能 | P3 | tags 为空时无显示 |

**后端**: `StudentController.emotion-report()` 依赖 aggregation，无数据返回。

### 5. SchoolTree.vue — 学校组织树

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 无学生节点（已删除） | 🐛 数据 | P2 | Fallback 显示 face 节点 |
| face 节点仅有 "人脸#ID" 无有意义名称 | 🧹 体验 | P3 | 无法识别是谁的脸 |
| 点击 face 节点加载情绪数据为空 | 🐛 数据 | P2 | face_record 无 student_id |
| 情绪分布图 ECharts legend 已修复 | ✅ 已修 | — | 只保留 ['快乐', '悲伤'] |
| 人脸缩略图通过 `/images/` 代理加载 | ✅ 已修 | — | WebConfig + Vite proxy |

### 6. FaceClusterPage.vue — 人脸聚类

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 聚类列表为空（cluster 已全部删除） | 🐛 数据 | P2 | 需重新聚类 |
| 预览列使用硬编码头像 | 🧹 体验 | P3 | `<el-avatar icon="UserFilled" />` |
| 合并功能未实现 | 🏗️ 缺失 | P3 | `openMerge` → `ElMessage.info('合并功能开发中')` |
| 重命名功能已实现 | ✅ 已修 | — | renameCluster API |
| 默认名 student001... 显示正确 | ✅ 已修 | — | autoAnnotateClusters() |

### 7. AdminPage.vue — 系统管理

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 年级管理：编辑/删除/新增按钮无功能 | 🏗️ 缺失 | P2 | 无后端 CRUD 端点 |
| 班级管理：编辑/创建人脸库按钮无功能 | 🏗️ 缺失 | P2 | 无后端端点 |
| 系统配置：全部硬编码，编辑按钮无功能 | 🏗️ 缺失 | P2 | 无配置读写 API |
| Pipeline 状态卡片 API 返回格式不一致 | 🐛 数据 | P2 | `res as any` 可能获取不到 `data` 字段 |
| 导入按钮调用 `/admin/import` 可能路径错误 | 🐛 功能 | P2 | 正确路径 `/admin/pipeline/import` |
| 实时日志 WebSocket 重连机制正常 | ✅ 正常 | — | 有 STOMP fallback to polling |
| `console.log` 调试日志 | ✅ 已修 | — | 已移除 |

### 8. AlertRulePage.vue — 预警规则

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 启用/禁用切换未实现 | 🏗️ 缺失 | P2 | `toggleRule` → "功能开发中" |
| 无编辑规则功能 | 🏗️ 缺失 | P3 | 仅有新建 |
| 无删除规则功能 | 🏗️ 缺失 | P3 | 仅有新建 |
| 后端 `/alert-rules` 端点存在 | ✅ 正常 | — | CRUD 部分实现 |

### 9. PipelineMonitor.vue — 管线监控

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 整体功能正常 | ✅ 正常 | — | 进度条/目录树/WebSocket |
| `console.log` 已移除 | ✅ 已修 | — | |
| 目录树自动刷新每 10s | ✅ 正常 | — | |

### 10. LoginPage.vue — 登录

| 问题 | 类型 | 严重度 | 说明 |
|------|------|:------:|------|
| 测试账号硬编码在模板 | 🧹 整洁 | P3 | `<p>测试账号: admin / 123456</p>` |
| JWT 认证流程正常 | ✅ 正常 | — | 401 → redirect to /login |

---

## 三、汇总

| 分类 | 数量 | 说明 |
|:----:|:----:|------|
| P0 — 功能不可用 | 3 | 班级看板无数据、座位热力图无数据、学生画像无数据 |
| P1 — 核心缺陷 | 6 | Mock 数据、关键接口未实现、数据依赖断裂 |
| P2 — 功能缺失 | 14 | 多处按钮无功能、后端接口未实现、配置页不可用 |
| P3 — 体验优化 | 4 | 硬编码、默认头像、标签、日志 |
| **合计** | **27** | |

### 优先修复建议

| 优先级 | 修复项 | 工作量 | 依赖 |
|:------:|--------|:------:|------|
| P0 | 修复引擎人脸检测（当前唯一阻塞） | 大 | 引擎工程 |
| P1 | student_id 回填机制（让旧数据可用） | 中 | 引擎修复后 |
| P1 | aggregation 数据填充 | 小 | student_id 修复后 |
| P2 | SeatHeatmap 数据结构 | 大 | 需新增 seat 表 |
| P2 | AdminPage 年级/班级 CRUD | 中 | 后端 Controller |
| P2 | 趋势图后端接口 | 小 | 无依赖 |
