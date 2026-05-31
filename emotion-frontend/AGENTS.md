# Frontend — emotion-frontend

**Stack:** Vue 3 + TypeScript + Vite + Element Plus + ECharts + Pinia

## STRUCTURE

```
src/
├── views/          # 页面组件 (11 个)
├── api/            # Axios API 客户端
├── components/     # 通用可复用组件
├── stores/         # Pinia 状态管理
├── router/         # Vue Router 配置
├── styles/         # 全局样式
└── types/          # TypeScript 类型定义
```

## PAGES

| 页面 | 文件 | 功能 |
|------|------|------|
| 管线监控 | `PipelineMonitor.vue` | 进度条 + 目录树 + WebSocket 日志 |
| 学校总览 | `SchoolOverview.vue` | 校级 KPI + 趋势图 |
| 班级看板 | `ClassDashboard.vue` | 班级情绪时间线 |
| 学校组织 | `SchoolTree.vue` | 年级→班级→学生/人脸树 |
| 学生画像 | `StudentProfile.vue` | 个人情绪趋势 |
| 人脸聚类 | `FaceClusterPage.vue` | 聚类列表 + 重命名 |
| 座位热图 | `SeatHeatmap.vue` | 座位情绪分布 |
| 系统管理 | `AdminPage.vue` | 告警规则 + 管线控制 |
| 告警规则 | `AlertRulePage.vue` | 告警规则 CRUD |
| 登录 | `LoginPage.vue` | JWT 登录 |

## CONVENTIONS

- **API 路径**: `src/api/*.ts`, 每个模块一个文件
- **认证**: axios interceptor 自动注入 Bearer token
- **WebSocket**: STOMP.js + SockJS, 动态协议 `ws:` / `wss:`
- **状态管理**: Pinia stores, 按模块拆分
- **路由**: 懒加载 `() => import(...)`
- **UI 库**: Element Plus (el-* 组件)
- **图表**: ECharts, 通过 `ref` + `nextTick` 初始化

## KNOWN ISSUES

- `AdminPage.vue` 有 `reduce` 类型推导问题 (需 `as number[]`)
- 人脸图片 URL 通过后端 `/images/` 代理访问
- Vite proxy `/api` + `/ws` + `/images` → localhost:8090
