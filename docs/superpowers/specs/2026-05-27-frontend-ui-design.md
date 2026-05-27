# 明眸情绪感知平台 — 前端UI设计规范

> 版本: v1.0
> 日期: 2026-05-27
> 技术栈: Vue 3 + Vite + Element Plus + ECharts 5 + Pinia
> 参考 PRD: `docs/superpowers/specs/2026-05-27-student-emotion-management-platform-prd.md`

---

## 1. 设计系统

### 1.1 整体风格

**Data-Dense Dashboard** — 数据密集型仪表盘。面向教师和管理者，追求清晰、高效、可操作，非消费级花哨风格。

### 1.2 色彩系统

| 角色 | 色值 | CSS 变量 | 用途 |
|------|------|----------|------|
| Primary | `#1E40AF` | `--color-primary` | 导航栏、主按钮、图表主色 |
| On Primary | `#FFFFFF` | `--color-on-primary` | 主色上的文字 |
| Secondary | `#3B82F6` | `--color-secondary` | 二级按钮、链接、焦点态 |
| Accent | `#D97706` | `--color-accent` | CTA按钮、高亮数据、预警标记 |
| Background | `#F8FAFC` | `--color-bg` | 页面底色 |
| Foreground | `#1E3A8A` | `--color-fg` | 正文文字 |
| Card | `#FFFFFF` | `--color-card` | 卡片/表格背景 |
| Muted | `#E9EEF6` | `--color-muted` | 次要背景 |
| Border | `#DBEAFE` | `--color-border` | 边框/分隔线 |
| Destructive | `#DC2626` | `--color-destructive` | 删除操作、严重异常 |
| Ring | `#1E40AF` | `--color-ring` | 焦点环 |

**情绪语义色彩（双通道编码——颜色+图标）：**

| 情绪 | 颜色 | 图标 | 语义 |
|------|------|------|------|
| 快乐 | `#22C55E` | `😊` (Heroicons: face-smile) | 正面 |
| 惊讶 | `#F59E0B` | `😲` (Heroicons: face-surprise) | 中性偏正面 |
| 中性 | `#64748B` | `😐` (Heroicons: face-neutral) | 中性 |
| 悲伤 | `#F97316` | `😢` (Heroicons: face-sad) | 需关注 |
| 愤怒 | `#DC2626` | `😠` (Heroicons: face-angry) | 需关注 |
| 恐惧 | `#7C3AED` | `😨` (Heroicons: face-fear) | 严重 |
| 厌恶 | `#374151` | `😖` (Heroicons: face-disgust) | 负面 |

### 1.3 字体系统

| 用途 | 字体 | 字号 |
|------|------|------|
| KPI 数字 | Fira Code | 28px / 700 |
| 页面标题 | Fira Sans | 24px / 600 |
| 区块标题 | Fira Sans | 18px / 600 |
| 正文 | Fira Sans | 14px / 400 |
| 辅助文字 | Fira Sans | 12px / 400 |
| 表格内容 | Fira Code (数字) / Fira Sans (文字) | 13px |

行高：正文 1.6，标题 1.3。

### 1.4 图标系统

- 库：Heroicons v2（24px, outline style）
- 禁止使用 Emoji 作为导航/系统图标
- 情绪图标可使用专用彩色 SVG（Heroicons 面部表情系列）
- 图标与文字对齐基线，统一 4px 间距

### 1.5 间距系统

基于 4px 递增：`4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48 / 64`

- 卡片内边距：16px
- 卡片间距：16px (grid gap)
- 区块间距：24px
- 页面水平内边距：32px（桌面）/ 24px（平板）

### 1.6 圆角 & 阴影

- 卡片圆角：8px
- 按钮圆角：6px
- 输入框圆角：6px
- 卡片阴影：`0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.06)`
- 弹出层阴影：`0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06)`

### 1.7 动画

- 微交互：150-200ms `ease-out`
- 页面过渡：200ms `ease-in-out`
- 弹出层：200ms `ease-out` + 轻微 scale (0.95→1)
- 图表数据更新：300ms `ease-out` 过渡
- 尊重 `prefers-reduced-motion`

---

## 2. 全局框架

### 2.1 布局结构

```
┌──────────────────────────────────────────────┐
│  Topbar (56px)                               │
├────────────┬─────────────────────────────────┤
│  Sidebar   │  Breadcrumb (32px)              │
│  (220px)   │  ─────────────────────────────  │
│            │  Content Area (flex:1)          │
│            │                                 │
└────────────┴─────────────────────────────────┘
```

### 2.2 顶栏 (Topbar)

| 元素 | 位置 | 说明 |
|------|------|------|
| Logo "明眸" | 左 | 点击返回校级大盘 |
| 当前角色标签 | Logo右侧 | `班主任 · 初一3班`，只读 |
| 预警铃铛 | 右 | WebSocket 实时推送未读数。0=灰色、1-5=琥珀、6+=红色脉冲 |
| 用户头像 | 最右 | 下拉菜单：切换班级/修改密码/退出 |

**预警铃铛浮层：**
- 宽度 320px，最大高度 400px
- 展示最近5条未读预警
- 每条：学生姓名 + 异常类型 + 时间 + "查看详情"链接
- 底部："查看全部预警 →" 跳转预警管理页

### 2.3 侧栏 (Sidebar)

```
📊 校级大盘    ← 管理员/校级/年级组长
📋 班级看板    ← 所有人（按权限过滤）
👤 个人档案    ← 所有人（按权限过滤）
──────────   ← 分隔线
⚠️ 预警管理    ← 管理员/校级
📝 干预记录    ← 班主任/心理老师
──────────
⚙️ 系统管理    ← 仅管理员
```

**状态：**
- 激活项：`bg-blue-50` + `text-blue-700` + 左侧 3px 蓝色指示条
- 默认：`text-slate-600` + hover `bg-slate-50`
- 无权限项：从 DOM 中移除，不渲染

**响应式：** <1024px 收起为 64px 图标模式，悬停展开。

### 2.4 面包屑 (Breadcrumb)

```
🏠 校级 > 初一 > 初一3班 > 张三
```

- 每段可点击，返回对应层级
- 分隔符 `text-slate-400`，可点击段 `text-slate-600`，当前段 `text-slate-900 font-medium`

---

## 3. 页面设计

### 3.1 校级大盘

**路由：** `/school/overview`
**用户：** 校级管理者、年级组长
**数据：** 全校/全年级聚合

**页面结构（从上到下）：**

1. **时间筛选栏** — 快捷按钮 `[上周] [本周] [本月] [本学期]` + 日期展示 + `[导出报表]`
2. **KPI 卡片行（4列）** — 情绪健康度、课堂参与度、异常情绪率、重点关注人数。每卡：图标 + 数值 + 环比变化 + 状态标签
3. **左右分栏**
   - 左：各年级情绪健康度对比（水平柱状图）。点击柱子展开该年级各班级子柱
   - 右：异常情绪率排行 Top 5（有序列表）。点击条目跳转班级看板
4. **全校情绪健康度趋势** — 面积图，展示各年级聚合趋势线。hover 浮层数值，支持缩放
5. **跨班级预警汇总** — 表格/列表。列：严重度图标、学生姓名、班级、异常描述、时间、操作按钮。已处理行灰色删除线

**数据刷新：** 页面加载时请求 API，预警列表通过 WebSocket 增量更新。

### 3.2 班级情绪看板

**路由：** `/class/:classId/dashboard`
**用户：** 班主任
**数据：** 单班实时 + 历史

**页面结构（从上到下）：**

1. **时间导航器** — `[←前日] 日期 │ [早读] [第1节] … [课外活动] [次日→]`
   - 12个时段水平滚动 tab
   - 当前时段蓝色下划线
   - 切换时整页数据刷新（骨架屏过渡）
2. **KPI 卡片行（4列）** — 快乐率、中性率、异常率、参与度。每卡含环比变化箭头
3. **课堂情绪时间线** — 堆叠面积图。X轴=时间点（每分钟采样），Y轴=各情绪比例%。hover 显示具体值，支持拖拽选取范围联动下方表格
4. **学生表情详情表格**
   - 列：姓名、学号、主导表情(图标+文字)、快乐%、中性%、悲伤%、愤怒%、参与度(进度条)、操作(👤跳转)
   - 排序：默认参与度降序；任意列可排序
   - 预警学生：红色左边框，始终置顶
   - 缺席学生：灰色底 + "—"
   - 搜索框中按姓名/学号筛选
   - 分页：每页20行
5. **底部双栏**
   - 左：座位热力图预览（7×4缩略矩阵 + "展开完整热力图 →"）
   - 右：本班重点关注卡片（学生名 + 趋势 sparkline + 快捷操作按钮）

**实时更新：** WebSocket `/ws/class/{classId}/emotion`，收到新表情结果时：更新 KPI 数值（数字动画）、追加图表数据点、更新表格对应行。

### 3.3 座位热力图

**路由：** `/class/:classId/heatmap`
**用户：** 班主任
**数据：** 按座位矩阵展示参与度

**页面结构（从上到下）：**

1. **时间选择器** — 日期 + 课时段下拉 + 课程标签
2. **座位矩阵**
   - 7列 × 4~6排（根据实际座位动态渲染）
   - 每格：参与度颜色（🟢≥70% 🟡40-69% 🟠<40% 🔴缺席 — 空位）+ 学生名 + 参与度%
   - 讲台标注在顶部
3. **hover 迷你卡片** — scale 1.15 + 浮层。内容：姓名、学号、本节参与度、主导表情图标、近3节 sparkline、"查看完整档案 →"
4. **分布统计** — 水平柱状图：高参与/中等/低/缺席/空位 人数及占比
5. **连续低参与提醒** — 列表展示连续N节低参与学生，附简要描述

**点击行为：** 点击任意学生座位 → 跳转该学生个人档案

### 3.4 个人情绪档案

**路由：** `/student/:studentId/profile`
**用户：** 班主任 / 心理老师 / 学生本人
**数据：** 单人跨时间维度

**页面结构（从上到下）：**

1. **学生信息头** — 姓名、学号、班级、标签系统（`[学业关注] [情绪关注] [行为关注]`，教师可编辑）
2. **KPI 卡片行（4列）** — 情绪指数(0-100)、参与度(含周环比)、异常次数(本周/累计)、样本数(本周/累计)
3. **情绪变化趋势图** — 多线折线图。切换：`[日] [周] [月] [学期]`，支持拖拽选取范围
4. **左右分栏**
   - 左：表情分布饼图（本周），hover高亮扇区
   - 右：各时段情绪对比（水平柱状图），点击某时段联动趋势图
5. **异常事件时间线** — 纵向时间线，每个节点：日期、时段、异常描述、触发值。点击节点跳转图表对应时间点
6. **干预记录（仅教师可见）** — 倒序列表。每条：日期、类型图标、操作人、原因、效果。支持展开/折叠。顶部 `[+ 记录干预]` 按钮

**记录干预抽屉（Modal Drawer，右侧滑出 480px）：**
- 干预类型下拉（个别谈话/家长沟通/心理辅导/同伴互助/其他）
- 干预日期选择器
- 干预描述文本框
- 关联异常事件复选框（可选）
- 保存/取消按钮

---

## 4. 组件层级

```
App.vue
├── AppLayout.vue                    # 全局框架
│   ├── AppTopbar.vue                # 顶栏
│   │   ├── AlertBadge.vue           # 预警铃铛（WebSocket）
│   │   └── UserMenu.vue             # 用户下拉菜单
│   ├── AppSidebar.vue               # 侧栏导航
│   └── AppBreadcrumb.vue            # 面包屑
│
├── views/
│   ├── SchoolOverview.vue           # 校级大盘
│   │   ├── KpiCardRow.vue
│   │   ├── GradeComparisonChart.vue # ECharts 水平柱状图
│   │   ├── AlertRankingList.vue
│   │   ├── EmotionTrendChart.vue    # ECharts 面积图
│   │   └── CrossClassAlertTable.vue
│   │
│   ├── ClassDashboard.vue           # 班级情绪看板
│   │   ├── TimeNavigator.vue        # 日期+时段选择器
│   │   ├── KpiCardRow.vue
│   │   ├── EmotionTimelineChart.vue # ECharts 堆叠面积图
│   │   ├── StudentEmotionTable.vue  # 学生表情表格
│   │   ├── SeatHeatmapPreview.vue   # 热力图缩略预览
│   │   └── FocusStudentCard.vue     # 重点关注卡片
│   │
│   ├── SeatHeatmap.vue             # 座位热力图
│   │   ├── TimeSelector.vue
│   │   ├── SeatMatrix.vue           # 座位矩阵
│   │   ├── StudentPopover.vue       # hover迷你卡片（Teleport）
│   │   ├── DistributionBar.vue      # 分布统计柱状图
│   │   └── LowEngagementAlert.vue
│   │
│   └── StudentProfile.vue          # 个人情绪档案
│       ├── StudentInfoHeader.vue    # 学生信息+标签
│       ├── KpiCardRow.vue
│       ├── EmotionTrendChart.vue    # ECharts 多线折线图
│       ├── EmotionPieChart.vue      # ECharts 饼图
│       ├── PeriodBarChart.vue       # ECharts 水平柱状图
│       ├── AlertTimeline.vue        # 异常事件时间线
│       ├── InterventionLog.vue      # 干预记录列表
│       └── InterventionDrawer.vue   # 记录干预抽屉
```

---

## 5. 数据流

### 5.1 状态管理 (Pinia)

```
stores/
├── useAuthStore.ts       # 当前用户、角色、权限范围(班级/年级ID)
├── useNavigationStore.ts # 当前激活路由、面包屑路径
├── useAlertStore.ts      # WebSocket预警推送、未读计数
├── useSchoolStore.ts     # 校级大盘数据（缓存5分钟）
├── useClassStore.ts      # 班级看板数据（当前班级、时段、日期）
└── useStudentStore.ts    # 学生档案数据
```

### 5.2 API 端点（前端调用）

| 端点 | 方法 | 用途 | 缓存 |
|------|------|------|------|
| `/api/v1/school/overview` | GET | 校级大盘数据 | 5min |
| `/api/v1/school/alerts` | GET | 跨班级预警列表 | 1min |
| `/api/v1/classes/{id}/dashboard` | GET | 班级看板数据 | 无（实时） |
| `/api/v1/classes/{id}/emotion-trend` | GET | 班级趋势数据 | 1min |
| `/api/v1/classes/{id}/heatmap` | GET | 座位热力图数据 | 无 |
| `/api/v1/students/{id}/emotion-timeline` | GET | 学生情绪时间线 | 1min |
| `/api/v1/students/{id}/emotion-report` | GET | 学生情绪报告 | 5min |
| `/api/v1/students/{id}/alerts` | GET | 学生异常事件 | 1min |
| `/api/v1/interventions` | POST | 记录干预 | — |
| `/ws/class/{classId}/emotion` | WS | 班级实时情绪 | — |
| `/ws/alerts` | WS | 预警实时推送 | — |

### 5.3 WebSocket 消息格式

```json
// 班级实时情绪推送
{
  "type": "emotion_update",
  "class_id": 1,
  "timestamp": "2026-05-27T10:15:00+08:00",
  "updates": [
    { "student_id": 1, "dominant_emotion": "happy", "dominant_confidence": 0.85, "engagement": 82 },
    { "student_id": 2, "dominant_emotion": "neutral", "dominant_confidence": 0.65, "engagement": 55 }
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

---

## 6. 路由设计

```
/                          → 重定向到 /school/overview 或 /class/:defaultClassId/dashboard
/school/overview           → 校级大盘（需管理员/校级/年级组长权限）
/class/:classId/dashboard  → 班级情绪看板（需对应班级权限）
/class/:classId/heatmap    → 座位热力图（需对应班级权限）
/student/:studentId/profile → 个人情绪档案（需对应学生权限）
/alerts                    → 预警管理（需管理员/校级权限）
/interventions             → 干预记录（需班主任/心理老师权限）
/admin                     → 系统管理（需管理员权限）
```

路由守卫：
- `beforeEach` 检查角色权限，无权限跳转 `/403`
- 班主任默认跳转到其负责的班级看板
- 校级管理员默认跳转到校级大盘

---

## 7. 响应式断点

| 断点 | 宽度 | 适配 |
|------|------|------|
| 桌面 | ≥1440px | 侧栏常驻220px，内容最大宽1440px居中 |
| 笔记本 | 1024-1439px | 侧栏常驻220px，内容自适应 |
| 平板横屏 | 768-1023px | 侧栏收起64px图标模式，KPI卡片2列 |
| 平板竖屏 | <768px | 侧栏汉堡菜单，KPI卡片1列，表格横向滚动 |

移动端（<768px）：仅支持预警通知查看，不建议完整操作。

---

## 8. 性能策略

- **路由懒加载**：每个 view 使用 `defineAsyncComponent` 或动态 import
- **ECharts 按需引入**：仅引入需要的图表类型（line, bar, pie, heatmap）
- **虚拟滚动**：学生表格 >50行时启用（Element Plus Table 支持）
- **WebSocket 断线重连**：指数退避重连（1s → 2s → 4s → 8s），上限30s
- **图表防抖**：resize 事件 200ms 防抖
- **API 请求去重**：相同参数的并发请求自动去重（Pinia + AbortController）
- **图片懒加载**：学生人脸图片使用 `loading="lazy"`

---

## 9. 无障碍 (Accessibility)

- 所有交互元素提供 `accessibilityLabel`
- 图表提供 `aria-label` 描述的文本摘要
- 表格使用语义化 `<table>` + `<th scope>`
- 颜色不是唯一信息载体：情绪始终搭配图标和文字
- 焦点环可见，Tab 键逻辑遍历
- `prefers-reduced-motion` 禁用过渡动画
- 预警通知使用 `aria-live="polite"` 区域播报

---

## 10. 技术依赖

| 库 | 版本 | 用途 |
|----|------|------|
| Vue | 3.4+ | 框架 |
| Vite | 5+ | 构建工具 |
| Element Plus | 2.6+ | UI 组件库（Table, Form, Dialog, Layout, Menu, Drawer） |
| ECharts | 5.5+ | 图表渲染 |
| Pinia | 2.1+ | 状态管理 |
| Vue Router | 4.3+ | 路由管理 |
| Socket.IO Client | 4.7+ | WebSocket |
| Heroicons | 2+ | 图标系统 |

---

## 11. 文件结构

```
emotion-frontend/
├── index.html
├── vite.config.ts
├── package.json
├── tsconfig.json
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── router/
│   │   └── index.ts                  # 路由配置 + 守卫
│   ├── stores/
│   │   ├── useAuthStore.ts
│   │   ├── useNavigationStore.ts
│   │   ├── useAlertStore.ts
│   │   ├── useSchoolStore.ts
│   │   ├── useClassStore.ts
│   │   └── useStudentStore.ts
│   ├── api/
│   │   ├── client.ts                 # Axios 实例 + 拦截器
│   │   ├── school.ts
│   │   ├── class.ts
│   │   ├── student.ts
│   │   └── websocket.ts             # Socket.IO 连接管理
│   ├── components/
│   │   ├── layout/
│   │   │   ├── AppLayout.vue
│   │   │   ├── AppTopbar.vue
│   │   │   ├── AppSidebar.vue
│   │   │   └── AppBreadcrumb.vue
│   │   ├── common/
│   │   │   ├── KpiCardRow.vue
│   │   │   ├── KpiCard.vue
│   │   │   ├── TimeNavigator.vue
│   │   │   ├── AlertBadge.vue
│   │   │   └── UserMenu.vue
│   │   ├── charts/
│   │   │   ├── EmotionTrendChart.vue
│   │   │   ├── EmotionTimelineChart.vue
│   │   │   ├── GradeComparisonChart.vue
│   │   │   ├── EmotionPieChart.vue
│   │   │   └── PeriodBarChart.vue
│   │   └── student/
│   │       ├── StudentPopover.vue
│   │       ├── AlertTimeline.vue
│   │       └── InterventionLog.vue
│   ├── views/
│   │   ├── SchoolOverview.vue
│   │   ├── ClassDashboard.vue
│   │   ├── SeatHeatmap.vue
│   │   ├── StudentProfile.vue
│   │   └── NotFound.vue
│   └── styles/
│       ├── variables.css             # CSS 变量（色彩、间距、字体）
│       ├── global.css                # 全局重置 + 基础样式
│       └── transitions.css           # 页面/组件过渡动画
└── public/
    └── favicon.svg
```
