# 明眸情绪感知平台 — 前端实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭建 Vue 3 + Element Plus 前端项目，实现校级大盘、班级情绪看板、座位热力图、个人情绪档案4个核心页面。

**Architecture:** Pinia 状态管理 + Axios API 层 + Socket.IO WebSocket 实时推送 + ECharts 图表渲染。全局框架（侧栏+顶栏+面包屑）包裹路由视图，组件按 layout/common/charts/student 分层。

**Tech Stack:** Vue 3.4 + Vite 5 + TypeScript + Element Plus 2.6 + ECharts 5.5 + Pinia 2.1 + Vue Router 4.3 + Socket.IO Client 4.7 + Vitest

**设计规范参考:** `docs/superpowers/specs/2026-05-27-frontend-ui-design.md`

---

## 文件结构

```
emotion-frontend/
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
├── tsconfig.node.json
├── env.d.ts
├── src/
│   ├── main.ts
│   ├── App.vue
│   ├── router/index.ts
│   ├── stores/
│   │   ├── useAuthStore.ts
│   │   ├── useNavigationStore.ts
│   │   ├── useAlertStore.ts
│   │   ├── useSchoolStore.ts
│   │   ├── useClassStore.ts
│   │   └── useStudentStore.ts
│   ├── api/
│   │   ├── client.ts
│   │   ├── school.ts
│   │   ├── class.ts
│   │   ├── student.ts
│   │   └── websocket.ts
│   ├── components/
│   │   ├── layout/
│   │   │   ├── AppLayout.vue
│   │   │   ├── AppTopbar.vue
│   │   │   ├── AppSidebar.vue
│   │   │   └── AppBreadcrumb.vue
│   │   ├── common/
│   │   │   ├── KpiCard.vue
│   │   │   ├── KpiCardRow.vue
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
│   ├── styles/
│   │   ├── variables.css
│   │   ├── global.css
│   │   └── transitions.css
│   └── types/
│       └── index.ts
└── src/__tests__/
    ├── stores/
    │   └── useAlertStore.test.ts
    ├── components/
    │   ├── KpiCard.test.ts
    │   └── TimeNavigator.test.ts
    └── views/
        └── ClassDashboard.test.ts
```

---

### Task 1: 项目脚手架 + 依赖安装

**Files:**
- Create: `emotion-frontend/package.json`
- Create: `emotion-frontend/vite.config.ts`
- Create: `emotion-frontend/tsconfig.json`
- Create: `emotion-frontend/tsconfig.node.json`
- Create: `emotion-frontend/index.html`
- Create: `emotion-frontend/env.d.ts`

- [ ] **Step 1: Create project directory and package.json**

```bash
mkdir -p emotion-frontend/src/{router,stores,api,components/{layout,common,charts,student},views,styles,types}
mkdir -p emotion-frontend/src/__tests__/{stores,components,views}
mkdir -p emotion-frontend/public
cd emotion-frontend
```

```json
// package.json
{
  "name": "emotion-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "vue-router": "^4.3.0",
    "pinia": "^2.1.0",
    "element-plus": "^2.6.0",
    "echarts": "^5.5.0",
    "vue-echarts": "^7.0.0",
    "socket.io-client": "^4.7.0",
    "axios": "^1.7.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^5.2.0",
    "vue-tsc": "^2.0.0",
    "typescript": "~5.4.0",
    "vitest": "^1.4.0",
    "@vue/test-utils": "^2.4.0",
    "jsdom": "^24.0.0",
    "@types/node": "^20.0.0"
  }
}
```

- [ ] **Step 2: Install dependencies**

```bash
cd emotion-frontend && npm install
```

Expected: install completes without errors.

- [ ] **Step 3: Create vite.config.ts**

```typescript
// vite.config.ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
```

- [ ] **Step 4: Create tsconfig.json**

```json
// tsconfig.json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "module": "ESNext",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "preserve",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": ["src/**/*.ts", "src/**/*.tsx", "src/**/*.vue", "env.d.ts"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

```json
// tsconfig.node.json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true
  },
  "include": ["vite.config.ts"]
}
```

- [ ] **Step 5: Create index.html and env.d.ts**

```html
<!-- index.html -->
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>明眸 · 学生身心健康管理平台</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

```typescript
// env.d.ts
/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
```

- [ ] **Step 6: Verify dev server starts**

```bash
cd emotion-frontend && npm run dev
```

Expected: Vite dev server starts on port 5173, blank page loads.

- [ ] **Step 7: Commit**

```bash
git add emotion-frontend/
git commit -m "feat: scaffold Vue 3 + Vite frontend project with all dependencies"
```

---

### Task 2: 设计系统 — CSS 变量 & 全局样式

**Files:**
- Create: `emotion-frontend/src/styles/variables.css`
- Create: `emotion-frontend/src/styles/global.css`
- Create: `emotion-frontend/src/styles/transitions.css`
- Create: `emotion-frontend/src/main.ts`
- Create: `emotion-frontend/src/App.vue`

- [ ] **Step 1: Create CSS variables from design system**

```css
/* src/styles/variables.css */
:root {
  /* Colors - Design System */
  --color-primary: #1E40AF;
  --color-on-primary: #FFFFFF;
  --color-secondary: #3B82F6;
  --color-accent: #D97706;
  --color-bg: #F8FAFC;
  --color-fg: #1E3A8A;
  --color-card: #FFFFFF;
  --color-muted: #E9EEF6;
  --color-muted-fg: #64748B;
  --color-border: #DBEAFE;
  --color-destructive: #DC2626;
  --color-ring: #1E40AF;

  /* Emotion Semantic Colors */
  --emotion-happy: #22C55E;
  --emotion-surprise: #F59E0B;
  --emotion-neutral: #64748B;
  --emotion-sad: #F97316;
  --emotion-angry: #DC2626;
  --emotion-fear: #7C3AED;
  --emotion-disgust: #374151;

  /* Typography */
  --font-heading: 'Fira Sans', sans-serif;
  --font-body: 'Fira Sans', sans-serif;
  --font-mono: 'Fira Code', monospace;
  --text-xs: 0.75rem;    /* 12px */
  --text-sm: 0.8125rem;  /* 13px */
  --text-base: 0.875rem; /* 14px */
  --text-lg: 1.125rem;   /* 18px */
  --text-xl: 1.5rem;     /* 24px */
  --text-2xl: 1.75rem;   /* 28px */
  --leading-body: 1.6;
  --leading-heading: 1.3;

  /* Spacing (4px system) */
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 20px;
  --space-6: 24px;
  --space-8: 32px;
  --space-10: 40px;
  --space-12: 48px;
  --space-16: 64px;

  /* Borders & Shadows */
  --radius-sm: 6px;
  --radius-md: 8px;
  --shadow-card: 0 1px 3px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.06);
  --shadow-popover: 0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06);

  /* Layout */
  --topbar-height: 56px;
  --sidebar-width: 220px;
  --sidebar-collapsed: 64px;
  --breadcrumb-height: 32px;
}

/* Dark mode overrides (future) */
[data-theme="dark"] {
  --color-bg: #0F172A;
  --color-fg: #F8FAFC;
  --color-card: #1E293B;
  --color-muted: #334155;
  --color-border: #475569;
}
```

- [ ] **Step 2: Create global styles**

```css
/* src/styles/global.css */
@import url('https://fonts.googleapis.com/css2?family=Fira+Code:wght@400;500;600;700&family=Fira+Sans:wght@300;400;500;600;700&display=swap');
@import './variables.css';

*, *::before, *::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html {
  font-size: 16px;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

body {
  font-family: var(--font-body);
  font-size: var(--text-base);
  line-height: var(--leading-body);
  color: var(--color-fg);
  background: var(--color-bg);
}

a {
  color: var(--color-secondary);
  text-decoration: none;
}
a:hover { text-decoration: underline; }

button {
  cursor: pointer;
  font-family: inherit;
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

- [ ] **Step 3: Create transitions**

```css
/* src/styles/transitions.css */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease-in-out;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.slide-right-enter-active,
.slide-right-leave-active {
  transition: transform 0.2s ease-out, opacity 0.2s ease-out;
}
.slide-right-enter-from {
  transform: translateX(-20px);
  opacity: 0;
}
.slide-right-leave-to {
  transform: translateX(20px);
  opacity: 0;
}

.scale-fade-enter-active,
.scale-fade-leave-active {
  transition: transform 0.2s ease-out, opacity 0.2s ease-out;
}
.scale-fade-enter-from {
  transform: scale(0.95);
  opacity: 0;
}
.scale-fade-leave-to {
  transform: scale(0.95);
  opacity: 0;
}
```

- [ ] **Step 4: Create main.ts with Element Plus**

```typescript
// src/main.ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles/global.css'
import './styles/transitions.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { size: 'default', zIndex: 3000 })
app.mount('#app')
```

- [ ] **Step 5: Create minimal App.vue for verification**

```vue
<!-- src/App.vue -->
<template>
  <router-view />
</template>

<script setup lang="ts">
</script>
```

- [ ] **Step 6: Verify dev server shows styled app shell**

```bash
cd emotion-frontend && npm run dev
```

Expected: No console errors. CSS variables loaded.

- [ ] **Step 7: Commit**

```bash
git add emotion-frontend/src/main.ts emotion-frontend/src/App.vue emotion-frontend/src/styles/
git commit -m "feat: design system CSS variables and global styles"
```

---

### Task 3: 路由 & 类型定义 & API 层

**Files:**
- Create: `emotion-frontend/src/types/index.ts`
- Create: `emotion-frontend/src/api/client.ts`
- Create: `emotion-frontend/src/api/school.ts`
- Create: `emotion-frontend/src/api/class.ts`
- Create: `emotion-frontend/src/api/student.ts`
- Create: `emotion-frontend/src/api/websocket.ts`
- Create: `emotion-frontend/src/router/index.ts`

- [ ] **Step 1: Define TypeScript types**

```typescript
// src/types/index.ts

// User roles matching PRD
export type UserRole = 'admin' | 'school_manager' | 'grade_leader' | 'teacher' | 'counselor' | 'student' | 'parent'

export interface UserInfo {
  id: number
  name: string
  role: UserRole
  gradeId?: number
  classId?: number
  studentId?: number
}

// Emotion data
export type EmotionType = 'happy' | 'sad' | 'angry' | 'surprise' | 'fear' | 'disgust' | 'neutral'

export interface EmotionDistribution {
  happy: number
  sad: number
  angry: number
  surprise: number
  fear: number
  disgust: number
  neutral: number
}

export interface EmotionRecord extends EmotionDistribution {
  dominant_emotion: EmotionType
  dominant_confidence: number
}

// KPI data
export interface KpiData {
  label: string
  value: number
  unit: string
  change: number | null       // 环比变化百分比
  changeDirection: 'up' | 'down' | 'flat'
  status: 'good' | 'warning' | 'danger' | 'neutral'
}

// Student-related
export interface StudentRow {
  id: number
  name: string
  studentNo: string
  dominantEmotion: EmotionType
  dominantConfidence: number
  happy: number
  neutral: number
  sad: number
  angry: number
  engagement: number
  isAlert: boolean
  isAbsent: boolean
}

// Seat heatmap
export interface SeatData {
  row: number
  col: number
  studentId: number | null
  studentName: string
  studentNo: string
  engagement: number | null
  dominantEmotion: EmotionType | null
  isAbsent: boolean
  isEmpty: boolean
}

// Alert
export interface AlertItem {
  id: number
  studentId: number
  studentName: string
  className: string
  type: string
  severity: 'high' | 'medium' | 'low'
  message: string
  timestamp: string
  acknowledged: boolean
}

// Intervention
export interface InterventionRecord {
  id: number
  studentId: number
  teacherName: string
  actionType: string
  description: string
  effect: string
  createdAt: string
}

// API response wrapper
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

// Dashboard data
export interface SchoolOverviewData {
  kpis: KpiData[]
  gradeComparison: { name: string; value: number; classes: { name: string; value: number }[] }[]
  alertRanking: { className: string; rate: number }[]
  trendData: { date: string; value: number; grade: string }[]
  crossClassAlerts: AlertItem[]
}

export interface ClassDashboardData {
  classId: number
  className: string
  date: string
  periodLabel: string
  kpis: KpiData[]
  timelineData: { time: string } & EmotionDistribution[]
  students: StudentRow[]
  totalPages: number
}

export interface SeatHeatmapData {
  seats: SeatData[]
  rows: number
  cols: number
  distribution: { label: string; count: number; pct: number }[]
  lowEngagementAlerts: { studentName: string; seatInfo: string; consecutiveClasses: number; desc: string }[]
}

export interface StudentProfileData {
  studentId: number
  studentName: string
  studentNo: string
  className: string
  tags: string[]
  kpis: KpiData[]
  trendData: { date: string } & EmotionDistribution[]
  weekDistribution: EmotionDistribution
  periodComparison: { period: string; value: number }[]
  alertTimeline: { date: string; period: string; desc: string; triggerValue: number }[]
  interventions: InterventionRecord[]
}

// WebSocket messages
export interface WsEmotionUpdate {
  type: 'emotion_update'
  class_id: number
  timestamp: string
  updates: { student_id: number; dominant_emotion: string; dominant_confidence: number; engagement: number }[]
}

export interface WsAlert {
  type: 'alert'
  alert_id: number
  student_name: string
  class_name: string
  message: string
  severity: 'high' | 'medium' | 'low'
  timestamp: string
}
```

- [ ] **Step 2: Create Axios client**

```typescript
// src/api/client.ts
import axios from 'axios'
import type { ApiResponse } from '@/types'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
})

// Response interceptor: unwrap { code, message, data }
client.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    if (body.code !== 0) {
      return Promise.reject(new Error(body.message || 'API error'))
    }
    response.data = body.data
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default client
```

- [ ] **Step 3: Create API modules**

```typescript
// src/api/school.ts
import client from './client'
import type { SchoolOverviewData } from '@/types'

export function fetchSchoolOverview(params?: { grade_id?: number; period?: string }): Promise<SchoolOverviewData> {
  return client.get('/school/overview', { params }).then(r => r.data as SchoolOverviewData)
}

export function fetchSchoolAlerts(params?: { status?: string }): Promise<import('@/types').AlertItem[]> {
  return client.get('/school/alerts', { params }).then(r => r.data as import('@/types').AlertItem[])
}
```

```typescript
// src/api/class.ts
import client from './client'
import type { ClassDashboardData, SeatHeatmapData } from '@/types'

export function fetchClassDashboard(classId: number, params: { date?: string; period_label?: string }): Promise<ClassDashboardData> {
  return client.get(`/classes/${classId}/dashboard`, { params }).then(r => r.data as ClassDashboardData)
}

export function fetchClassTrend(classId: number, params: { start?: string; end?: string }): Promise<unknown> {
  return client.get(`/classes/${classId}/emotion-trend`, { params }).then(r => r.data)
}

export function fetchSeatHeatmap(classId: number, params: { date?: string; period_label?: string }): Promise<SeatHeatmapData> {
  return client.get(`/classes/${classId}/heatmap`, { params }).then(r => r.data as SeatHeatmapData)
}
```

```typescript
// src/api/student.ts
import client from './client'
import type { StudentProfileData } from '@/types'

export function fetchStudentProfile(studentId: number, params?: { date?: string; period?: string }): Promise<StudentProfileData> {
  return client.get(`/students/${studentId}/emotion-timeline`, { params }).then(r => r.data as StudentProfileData)
}

export function fetchStudentReport(studentId: number, params?: { start?: string; end?: string }): Promise<unknown> {
  return client.get(`/students/${studentId}/emotion-report`, { params }).then(r => r.data)
}

export function fetchStudentAlerts(studentId: number): Promise<import('@/types').AlertItem[]> {
  return client.get(`/students/${studentId}/alerts`).then(r => r.data as import('@/types').AlertItem[])
}

export function createIntervention(data: { student_id: number; action_type: string; description: string; effect?: string }): Promise<void> {
  return client.post('/interventions', data)
}
```

```typescript
// src/api/websocket.ts
import { io, Socket } from 'socket.io-client'
import type { WsEmotionUpdate, WsAlert } from '@/types'

let classSocket: Socket | null = null
let alertSocket: Socket | null = null

export function connectClassSocket(classId: number, onUpdate: (data: WsEmotionUpdate) => void): Socket {
  if (classSocket) classSocket.disconnect()
  classSocket = io(`/ws/class/${classId}/emotion`, {
    transports: ['websocket'],
    reconnectionDelayMax: 30000,
  })
  classSocket.on('message', onUpdate)
  classSocket.on('connect_error', (err) => console.error('Class WS error:', err.message))
  return classSocket
}

export function disconnectClassSocket(): void {
  classSocket?.disconnect()
  classSocket = null
}

export function connectAlertSocket(onAlert: (data: WsAlert) => void): Socket {
  if (alertSocket) alertSocket.disconnect()
  alertSocket = io('/ws/alerts', {
    transports: ['websocket'],
    reconnectionDelayMax: 30000,
  })
  alertSocket.on('message', onAlert)
  alertSocket.on('connect_error', (err) => console.error('Alert WS error:', err.message))
  return alertSocket
}

export function disconnectAlertSocket(): void {
  alertSocket?.disconnect()
  alertSocket = null
}
```

- [ ] **Step 4: Create router with guards**

```typescript
// src/router/index.ts
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: () => {
      // In real app, redirect based on user role from auth store
      return '/school/overview'
    },
  },
  {
    path: '/school/overview',
    name: 'SchoolOverview',
    component: () => import('@/views/SchoolOverview.vue'),
    meta: { roles: ['admin', 'school_manager', 'grade_leader'] },
  },
  {
    path: '/class/:classId/dashboard',
    name: 'ClassDashboard',
    component: () => import('@/views/ClassDashboard.vue'),
    props: true,
  },
  {
    path: '/class/:classId/heatmap',
    name: 'SeatHeatmap',
    component: () => import('@/views/SeatHeatmap.vue'),
    props: true,
  },
  {
    path: '/student/:studentId/profile',
    name: 'StudentProfile',
    component: () => import('@/views/StudentProfile.vue'),
    props: true,
  },
  {
    path: '/alerts',
    name: 'Alerts',
    component: () => import('@/views/NotFound.vue'), // Placeholder
    meta: { roles: ['admin', 'school_manager'] },
  },
  {
    path: '/interventions',
    name: 'Interventions',
    component: () => import('@/views/NotFound.vue'), // Placeholder
    meta: { roles: ['teacher', 'counselor'] },
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('@/views/NotFound.vue'), // Placeholder
    meta: { roles: ['admin'] },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFound.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
```

- [ ] **Step 5: Create minimal NotFound.vue**

```vue
<!-- src/views/NotFound.vue -->
<template>
  <div style="display:flex;align-items:center;justify-content:center;height:100vh;flex-direction:column;gap:16px">
    <h1 style="font-size:48px;color:var(--color-muted-fg)">404</h1>
    <p>页面未找到</p>
    <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
  </div>
</template>
```

- [ ] **Step 6: Verify routing works**

```bash
cd emotion-frontend && npm run dev
# Navigate to http://localhost:5173/ — should redirect to /school/overview with blank page
# Navigate to http://localhost:5173/nonexistent — should show 404
```

- [ ] **Step 7: Commit**

```bash
git add emotion-frontend/src/types/ emotion-frontend/src/api/ emotion-frontend/src/router/ emotion-frontend/src/views/NotFound.vue
git commit -m "feat: types, API layer, router with route guards"
```

---

### Task 4: Pinia 状态管理

**Files:**
- Create: `emotion-frontend/src/stores/useAuthStore.ts`
- Create: `emotion-frontend/src/stores/useNavigationStore.ts`
- Create: `emotion-frontend/src/stores/useAlertStore.ts`
- Create: `emotion-frontend/src/stores/useSchoolStore.ts`
- Create: `emotion-frontend/src/stores/useClassStore.ts`
- Create: `emotion-frontend/src/stores/useStudentStore.ts`
- Create: `emotion-frontend/src/__tests__/stores/useAlertStore.test.ts`

- [ ] **Step 1: Write failing test for useAlertStore**

```typescript
// src/__tests__/stores/useAlertStore.test.ts
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAlertStore } from '@/stores/useAlertStore'

describe('useAlertStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('starts with zero unread count', () => {
    const store = useAlertStore()
    expect(store.unreadCount).toBe(0)
  })

  it('adds alert and increments unread count', () => {
    const store = useAlertStore()
    store.addAlert({
      id: 1, studentId: 1, studentName: '张三', className: '初一3班',
      type: 'sad', severity: 'high', message: '连续悲伤',
      timestamp: '2026-05-27T10:00:00+08:00', acknowledged: false,
    })
    expect(store.unreadCount).toBe(1)
    expect(store.alerts).toHaveLength(1)
  })

  it('marks alert as read', () => {
    const store = useAlertStore()
    store.addAlert({
      id: 1, studentId: 1, studentName: '张三', className: '初一3班',
      type: 'sad', severity: 'high', message: '连续悲伤',
      timestamp: '2026-05-27T10:00:00+08:00', acknowledged: false,
    })
    store.markRead(1)
    expect(store.unreadCount).toBe(0)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd emotion-frontend && npx vitest run src/__tests__/stores/useAlertStore.test.ts
```

Expected: FAIL — `useAlertStore` module not found.

- [ ] **Step 3: Implement useAuthStore**

```typescript
// src/stores/useAuthStore.ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserInfo, UserRole } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(null)
  const isLoggedIn = computed(() => user.value !== null)
  const role = computed(() => user.value?.role ?? null)

  function setUser(u: UserInfo) {
    user.value = u
  }

  function hasRole(roles: UserRole[]): boolean {
    if (!user.value) return false
    return roles.includes(user.value.role)
  }

  function canViewStudent(studentId: number): boolean {
    if (!user.value) return false
    if (user.value.role === 'admin' || user.value.role === 'counselor') return true
    if (user.value.role === 'student' || user.value.role === 'parent') {
      return user.value.studentId === studentId
    }
    return false // Others need class-level check
  }

  function canViewClass(classId: number): boolean {
    if (!user.value) return false
    if (user.value.role === 'admin' || user.value.role === 'counselor') return true
    if (user.value.role === 'teacher') {
      return user.value.classId === classId
    }
    return false
  }

  return { user, isLoggedIn, role, setUser, hasRole, canViewStudent, canViewClass }
})
```

- [ ] **Step 4: Implement useNavigationStore**

```typescript
// src/stores/useNavigationStore.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface BreadcrumbItem {
  label: string
  to?: string
}

export const useNavigationStore = defineStore('navigation', () => {
  const breadcrumbs = ref<BreadcrumbItem[]>([])
  const activeNavItem = ref('')

  function setBreadcrumbs(items: BreadcrumbItem[]) {
    breadcrumbs.value = items
  }

  function setActiveNav(item: string) {
    activeNavItem.value = item
  }

  return { breadcrumbs, activeNavItem, setBreadcrumbs, setActiveNav }
})
```

- [ ] **Step 5: Implement useAlertStore**

```typescript
// src/stores/useAlertStore.ts
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { AlertItem } from '@/types'

export const useAlertStore = defineStore('alert', () => {
  const alerts = ref<AlertItem[]>([])
  const unreadCount = computed(() => alerts.value.filter(a => !a.acknowledged).length)
  const recentAlerts = computed(() => alerts.value.filter(a => !a.acknowledged).slice(0, 5))

  function addAlert(alert: AlertItem) {
    alerts.value.unshift(alert)
    if (alerts.value.length > 100) alerts.value.pop()
  }

  function markRead(id: number) {
    const alert = alerts.value.find(a => a.id === id)
    if (alert) alert.acknowledged = true
  }

  function markAllRead() {
    alerts.value.forEach(a => { a.acknowledged = true })
  }

  return { alerts, unreadCount, recentAlerts, addAlert, markRead, markAllRead }
})
```

- [ ] **Step 6: Implement remaining stores**

```typescript
// src/stores/useSchoolStore.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { SchoolOverviewData } from '@/types'
import { fetchSchoolOverview, fetchSchoolAlerts } from '@/api/school'

export const useSchoolStore = defineStore('school', () => {
  const overviewData = ref<SchoolOverviewData | null>(null)
  const loading = ref(false)

  async function loadOverview(params?: { grade_id?: number; period?: string }) {
    loading.value = true
    try {
      overviewData.value = await fetchSchoolOverview(params)
    } finally {
      loading.value = false
    }
  }

  return { overviewData, loading, loadOverview }
})
```

```typescript
// src/stores/useClassStore.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ClassDashboardData, SeatHeatmapData } from '@/types'
import { fetchClassDashboard, fetchSeatHeatmap } from '@/api/class'

export const useClassStore = defineStore('class', () => {
  const dashboardData = ref<ClassDashboardData | null>(null)
  const heatmapData = ref<SeatHeatmapData | null>(null)
  const loading = ref(false)
  const currentPeriod = ref('')
  const currentDate = ref('')

  async function loadDashboard(classId: number, params: { date?: string; period_label?: string }) {
    loading.value = true
    try {
      dashboardData.value = await fetchClassDashboard(classId, params)
      currentPeriod.value = params.period_label || ''
      currentDate.value = params.date || ''
    } finally {
      loading.value = false
    }
  }

  async function loadHeatmap(classId: number, params: { date?: string; period_label?: string }) {
    loading.value = true
    try {
      heatmapData.value = await fetchSeatHeatmap(classId, params)
    } finally {
      loading.value = false
    }
  }

  function updateFromWs(updates: { student_id: number; dominant_emotion: string; dominant_confidence: number; engagement: number }[]) {
    if (!dashboardData.value) return
    for (const update of updates) {
      const student = dashboardData.value.students.find(s => s.id === update.student_id)
      if (student) {
        student.dominantEmotion = update.dominant_emotion as any
        student.dominantConfidence = update.dominant_confidence
        student.engagement = update.engagement
      }
    }
  }

  return { dashboardData, heatmapData, loading, currentPeriod, currentDate, loadDashboard, loadHeatmap, updateFromWs }
})
```

```typescript
// src/stores/useStudentStore.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { StudentProfileData } from '@/types'
import { fetchStudentProfile } from '@/api/student'

export const useStudentStore = defineStore('student', () => {
  const profileData = ref<StudentProfileData | null>(null)
  const loading = ref(false)

  async function loadProfile(studentId: number, params?: { date?: string; period?: string }) {
    loading.value = true
    try {
      profileData.value = await fetchStudentProfile(studentId, params)
    } finally {
      loading.value = false
    }
  }

  return { profileData, loading, loadProfile }
})
```

- [ ] **Step 7: Run tests**

```bash
cd emotion-frontend && npx vitest run
```

Expected: 3 tests in useAlertStore PASS.

- [ ] **Step 8: Commit**

```bash
git add emotion-frontend/src/stores/ emotion-frontend/src/__tests__/
git commit -m "feat: Pinia stores with auth, nav, alert, school, class, student state"
```

---

### Task 5: 全局框架组件 (AppLayout + Topbar + Sidebar + Breadcrumb)

**Files:**
- Create: `emotion-frontend/src/components/layout/AppLayout.vue`
- Create: `emotion-frontend/src/components/layout/AppTopbar.vue`
- Create: `emotion-frontend/src/components/layout/AppSidebar.vue`
- Create: `emotion-frontend/src/components/layout/AppBreadcrumb.vue`
- Modify: `emotion-frontend/src/App.vue`

- [ ] **Step 1: Create AppLayout.vue**

```vue
<!-- src/components/layout/AppLayout.vue -->
<template>
  <div class="app-layout">
    <AppTopbar />
    <div class="app-body">
      <AppSidebar />
      <main class="app-main">
        <AppBreadcrumb />
        <div class="app-content">
          <slot />
        </div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import AppTopbar from './AppTopbar.vue'
import AppSidebar from './AppSidebar.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
</script>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}

.app-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.app-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.app-content {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6) var(--space-8);
  max-width: 1440px;
  width: 100%;
  margin: 0 auto;
}

@media (max-width: 1023px) {
  .app-content {
    padding: var(--space-4) var(--space-6);
  }
}
</style>
```

- [ ] **Step 2: Create AppTopbar.vue**

```vue
<!-- src/components/layout/AppTopbar.vue -->
<template>
  <header class="topbar">
    <div class="topbar-left">
      <span class="topbar-logo" @click="$router.push('/')">👁 明眸</span>
      <span class="topbar-title">学生身心健康管理平台</span>
      <span v-if="auth.user" class="topbar-role">{{ roleLabel }}</span>
    </div>
    <div class="topbar-right">
      <AlertBadge
        :count="alertStore.unreadCount"
        @click="showAlertPanel = !showAlertPanel"
      />
      <UserMenu />
    </div>
    <!-- Alert dropdown panel -->
    <Teleport to="body">
      <div v-if="showAlertPanel" class="alert-panel" @click.stop>
        <h4>预警通知</h4>
        <div v-if="alertStore.recentAlerts.length === 0" class="alert-empty">暂无未读预警</div>
        <div v-for="alert in alertStore.recentAlerts" :key="alert.id" class="alert-item"
             @click="goToStudent(alert.studentId); alertStore.markRead(alert.id)">
          <span :class="'severity-' + alert.severity">{{ severityIcon(alert.severity) }}</span>
          <div>
            <strong>{{ alert.studentName }}</strong> · {{ alert.className }}
            <p>{{ alert.message }} · {{ formatTime(alert.timestamp) }}</p>
          </div>
        </div>
        <div class="alert-footer">
          <el-button size="small" text @click="$router.push('/alerts'); showAlertPanel = false">查看全部预警 →</el-button>
        </div>
      </div>
      <div v-if="showAlertPanel" class="alert-overlay" @click="showAlertPanel = false" />
    </Teleport>
  </header>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/useAuthStore'
import { useAlertStore } from '@/stores/useAlertStore'
import AlertBadge from '@/components/common/AlertBadge.vue'
import UserMenu from '@/components/common/UserMenu.vue'

const router = useRouter()
const auth = useAuthStore()
const alertStore = useAlertStore()
const showAlertPanel = ref(false)

const roleLabel = computed(() => {
  if (!auth.user) return ''
  const labels: Record<string, string> = {
    admin: '系统管理员',
    school_manager: '校级管理者',
    grade_leader: '年级组长',
    teacher: '班主任',
    counselor: '心理辅导老师',
    student: '学生',
    parent: '家长',
  }
  const label = labels[auth.user.role] || ''
  const cls = auth.user.classId ? ` · 班级${auth.user.classId}` : ''
  return `${label}${cls}`
})

function severityIcon(s: string): string {
  return { high: '🔴', medium: '🟡', low: '🟢' }[s] || '⚪'
}

function formatTime(ts: string): string {
  if (!ts) return ''
  const d = new Date(ts)
  return `${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}

function goToStudent(id: number) {
  router.push(`/student/${id}/profile`)
}
</script>

<style scoped>
.topbar {
  height: var(--topbar-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-4);
  background: var(--color-primary);
  color: var(--color-on-primary);
  position: relative;
  z-index: 100;
  flex-shrink: 0;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.topbar-logo {
  font-family: var(--font-mono);
  font-weight: 700;
  font-size: var(--text-lg);
  cursor: pointer;
}

.topbar-title {
  font-size: var(--text-sm);
  opacity: 0.85;
}

.topbar-role {
  font-size: var(--text-xs);
  background: rgba(255,255,255,0.15);
  padding: 2px 10px;
  border-radius: var(--radius-sm);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.alert-panel {
  position: fixed;
  top: var(--topbar-height);
  right: 120px;
  width: 360px;
  max-height: 420px;
  overflow-y: auto;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-popover);
  z-index: 2000;
  padding: var(--space-4);
}

.alert-overlay {
  position: fixed;
  inset: 0;
  z-index: 1999;
}

.alert-item {
  display: flex;
  gap: var(--space-2);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--color-border);
  cursor: pointer;
}
.alert-item:hover { background: var(--color-muted); }
.alert-item p { font-size: var(--text-xs); color: var(--color-muted-fg); }
.alert-empty { text-align: center; color: var(--color-muted-fg); padding: var(--space-4); }
.alert-footer { text-align: center; padding-top: var(--space-2); }
.alert-footer button { color: var(--color-secondary); }
</style>
```

- [ ] **Step 3: Create AppSidebar.vue**

```vue
<!-- src/components/layout/AppSidebar.vue -->
<template>
  <aside class="sidebar" :class="{ collapsed }">
    <nav class="sidebar-nav">
      <template v-for="item in visibleItems" :key="item.key">
        <div v-if="item.separator" class="sidebar-separator" />
        <router-link
          v-else
          :to="item.to"
          class="sidebar-item"
          :class="{ active: isActive(item.key) }"
        >
          <span class="sidebar-icon">{{ item.icon }}</span>
          <span class="sidebar-label">{{ item.label }}</span>
        </router-link>
      </template>
    </nav>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/useAuthStore'
import { useNavigationStore } from '@/stores/useNavigationStore'

const route = useRoute()
const auth = useAuthStore()
const nav = useNavigationStore()
const collapsed = ref(false)

interface NavItem {
  key: string
  label: string
  icon: string
  to: string
  separator?: false
  roles?: string[]
}
interface SeparatorItem {
  separator: true
}

const allItems: (NavItem | SeparatorItem)[] = [
  { key: 'school', label: '校级大盘', icon: '📊', to: '/school/overview', roles: ['admin', 'school_manager', 'grade_leader'] },
  { key: 'class', label: '班级看板', icon: '📋', to: '/class/1/dashboard' },
  { key: 'student', label: '个人档案', icon: '👤', to: '/student/1/profile' },
  { separator: true },
  { key: 'alerts', label: '预警管理', icon: '⚠️', to: '/alerts', roles: ['admin', 'school_manager'] },
  { key: 'interventions', label: '干预记录', icon: '📝', to: '/interventions', roles: ['teacher', 'counselor'] },
  { separator: true },
  { key: 'admin', label: '系统管理', icon: '⚙️', to: '/admin', roles: ['admin'] },
]

const visibleItems = computed(() => {
  return allItems.filter(item => {
    if ('separator' in item) return true
    if (!item.roles) return true
    return auth.hasRole(item.roles as any)
  })
})

function isActive(key: string): boolean {
  if (key === 'school') return route.path.startsWith('/school')
  if (key === 'class') return route.path.startsWith('/class')
  if (key === 'student') return route.path.startsWith('/student')
  return route.path === (allItems.find(i => !('separator' in i) && i.key === key) as NavItem)?.to
}
</script>

<style scoped>
.sidebar {
  width: var(--sidebar-width);
  background: var(--color-card);
  border-right: 1px solid var(--color-border);
  flex-shrink: 0;
  overflow-y: auto;
  padding-top: var(--space-2);
  transition: width 0.2s ease-out;
}

.sidebar.collapsed { width: var(--sidebar-collapsed); }

.sidebar-nav {
  display: flex;
  flex-direction: column;
}

.sidebar-item {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  color: var(--color-muted-fg);
  text-decoration: none;
  font-size: var(--text-sm);
  border-left: 3px solid transparent;
  transition: all 0.15s ease-out;
}

.sidebar-item:hover {
  background: var(--color-muted);
  color: var(--color-fg);
  text-decoration: none;
}

.sidebar-item.active {
  background: #EFF6FF;
  color: var(--color-primary);
  border-left-color: var(--color-primary);
  font-weight: 500;
}

.sidebar-icon { font-size: 18px; }
.sidebar-label { white-space: nowrap; }

.sidebar-separator {
  height: 1px;
  background: var(--color-border);
  margin: var(--space-2) var(--space-4);
}

@media (max-width: 1023px) {
  .sidebar { width: var(--sidebar-collapsed); }
  .sidebar-label { display: none; }
}
</style>
```

- [ ] **Step 4: Create AppBreadcrumb.vue**

```vue
<!-- src/components/layout/AppBreadcrumb.vue -->
<template>
  <div class="breadcrumb" v-if="nav.breadcrumbs.length > 0">
    <template v-for="(item, i) in nav.breadcrumbs" :key="i">
      <span v-if="i > 0" class="breadcrumb-sep">›</span>
      <router-link v-if="item.to && i < nav.breadcrumbs.length - 1" :to="item.to" class="breadcrumb-link">
        {{ item.label }}
      </router-link>
      <span v-else class="breadcrumb-current">{{ item.label }}</span>
    </template>
  </div>
</template>

<script setup lang="ts">
import { useNavigationStore } from '@/stores/useNavigationStore'
const nav = useNavigationStore()
</script>

<style scoped>
.breadcrumb {
  height: var(--breadcrumb-height);
  display: flex;
  align-items: center;
  padding: 0 var(--space-8);
  font-size: var(--text-xs);
  flex-shrink: 0;
}

.breadcrumb-sep {
  color: #94A3B8;
  margin: 0 var(--space-2);
}

.breadcrumb-link {
  color: var(--color-muted-fg);
  text-decoration: none;
}
.breadcrumb-link:hover { color: var(--color-secondary); text-decoration: none; }

.breadcrumb-current {
  color: var(--color-fg);
  font-weight: 500;
}
</style>
```

- [ ] **Step 5: Create AlertBadge.vue and UserMenu.vue**

```vue
<!-- src/components/common/AlertBadge.vue -->
<template>
  <div class="alert-badge" @click="$emit('click')">
    <span class="badge-icon">🔔</span>
    <span v-if="count > 0" class="badge-count" :class="badgeClass">{{ count > 99 ? '99+' : count }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ count: number }>()
defineEmits(['click'])

const badgeClass = computed(() => {
  if (props.count >= 6) return 'badge-danger'
  if (props.count >= 1) return 'badge-warning'
  return ''
})
</script>

<style scoped>
.alert-badge {
  position: relative;
  cursor: pointer;
  padding: var(--space-2);
}
.badge-icon { font-size: 20px; }
.badge-count {
  position: absolute;
  top: 0;
  right: 0;
  min-width: 18px;
  height: 18px;
  border-radius: 9px;
  font-size: 10px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-muted-fg);
  color: white;
}
.badge-warning { background: var(--color-accent); }
.badge-danger { background: var(--color-destructive); animation: pulse 2s infinite; }
@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.15); }
}
</style>
```

```vue
<!-- src/components/common/UserMenu.vue -->
<template>
  <div class="user-menu" v-if="auth.user">
    <el-dropdown trigger="click">
      <span class="user-trigger">
        <el-avatar :size="32" icon="UserFilled" />
        <span class="user-name">{{ auth.user.name }}</span>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled>角色: {{ auth.user.role }}</el-dropdown-item>
          <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup lang="ts">
import { useAuthStore } from '@/stores/useAuthStore'
const auth = useAuthStore()

function handleLogout() {
  window.location.href = '/login'
}
</script>

<style scoped>
.user-menu { cursor: pointer; }
.user-trigger {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--color-on-primary);
}
.user-name { font-size: var(--text-sm); }
</style>
```

- [ ] **Step 6: Update App.vue to use layout**

```vue
<!-- src/App.vue -->
<template>
  <AppLayout>
    <router-view v-slot="{ Component }">
      <transition name="fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </AppLayout>
</template>

<script setup lang="ts">
import AppLayout from '@/components/layout/AppLayout.vue'
</script>
```

- [ ] **Step 7: Verify layout renders**

```bash
cd emotion-frontend && npm run dev
# Expected: topbar (blue) + sidebar (white) + breadcrumb area visible
```

- [ ] **Step 8: Commit**

```bash
git add emotion-frontend/src/components/layout/ emotion-frontend/src/components/common/AlertBadge.vue emotion-frontend/src/components/common/UserMenu.vue emotion-frontend/src/App.vue
git commit -m "feat: global layout framework (topbar + sidebar + breadcrumb)"
```

---

### Task 6: 通用组件 (KpiCard, TimeNavigator)

**Files:**
- Create: `emotion-frontend/src/components/common/KpiCard.vue`
- Create: `emotion-frontend/src/components/common/KpiCardRow.vue`
- Create: `emotion-frontend/src/components/common/TimeNavigator.vue`
- Create: `emotion-frontend/src/__tests__/components/KpiCard.test.ts`
- Create: `emotion-frontend/src/__tests__/components/TimeNavigator.test.ts`

- [ ] **Step 1: Write failing test for KpiCard**

```typescript
// src/__tests__/components/KpiCard.test.ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import KpiCard from '@/components/common/KpiCard.vue'

describe('KpiCard', () => {
  it('renders label and value', () => {
    const wrapper = mount(KpiCard, {
      props: { label: '快乐率', value: 72, unit: '%', change: 5, changeDirection: 'up' as const, status: 'good' as const }
    })
    expect(wrapper.text()).toContain('快乐率')
    expect(wrapper.text()).toContain('72')
    expect(wrapper.text()).toContain('%')
    expect(wrapper.text()).toContain('5') // change value
  })

  it('shows down arrow for negative change', () => {
    const wrapper = mount(KpiCard, {
      props: { label: '异常率', value: 3, unit: '%', change: -2, changeDirection: 'down' as const, status: 'good' as const }
    })
    expect(wrapper.text()).toContain('↓')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd emotion-frontend && npx vitest run src/__tests__/components/KpiCard.test.ts
```

Expected: FAIL — `KpiCard` not found.

- [ ] **Step 3: Implement KpiCard.vue**

```vue
<!-- src/components/common/KpiCard.vue -->
<template>
  <div class="kpi-card">
    <div class="kpi-label">{{ label }}</div>
    <div class="kpi-value">
      <span class="kpi-number">{{ value }}</span>
      <span class="kpi-unit">{{ unit }}</span>
      <span v-if="change !== null" class="kpi-change" :class="changeDirection">
        {{ changeDirection === 'up' ? '↑' : changeDirection === 'down' ? '↓' : '→' }}
        {{ Math.abs(change) }}%
      </span>
    </div>
    <div class="kpi-status" :class="`status-${status}`">{{ statusLabel }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  label: string
  value: number
  unit?: string
  change?: number | null
  changeDirection?: 'up' | 'down' | 'flat'
  status?: 'good' | 'warning' | 'danger' | 'neutral'
}>()

const statusLabel = computed(() => {
  const labels: Record<string, string> = {
    good: '正常范围',
    warning: '需关注',
    danger: '异常',
    neutral: '—',
  }
  return labels[props.status || 'neutral']
})
</script>

<style scoped>
.kpi-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  box-shadow: var(--shadow-card);
}

.kpi-label {
  font-size: var(--text-xs);
  color: var(--color-muted-fg);
  margin-bottom: var(--space-2);
}

.kpi-value {
  display: flex;
  align-items: baseline;
  gap: var(--space-1);
  margin-bottom: var(--space-2);
}

.kpi-number {
  font-family: var(--font-mono);
  font-size: var(--text-2xl);
  font-weight: 700;
  color: var(--color-fg);
}

.kpi-unit {
  font-size: var(--text-sm);
  color: var(--color-muted-fg);
}

.kpi-change {
  font-size: var(--text-xs);
  font-weight: 500;
  margin-left: auto;
}
.kpi-change.up { color: var(--emotion-happy); }
.kpi-change.down { color: var(--color-destructive); }
.kpi-change.flat { color: var(--color-muted-fg); }

.kpi-status {
  font-size: var(--text-xs);
}
.status-good { color: var(--emotion-happy); }
.status-warning { color: var(--color-accent); }
.status-danger { color: var(--color-destructive); }
.status-neutral { color: var(--color-muted-fg); }
</style>
```

- [ ] **Step 4: Implement KpiCardRow.vue**

```vue
<!-- src/components/common/KpiCardRow.vue -->
<template>
  <div class="kpi-row">
    <KpiCard v-for="(kpi, i) in kpis" :key="i" v-bind="kpi" />
  </div>
</template>

<script setup lang="ts">
import KpiCard from './KpiCard.vue'
import type { KpiData } from '@/types'

defineProps<{ kpis: KpiData[] }>()
</script>

<style scoped>
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

@media (max-width: 1023px) {
  .kpi-row { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 767px) {
  .kpi-row { grid-template-columns: 1fr; }
}
</style>
```

- [ ] **Step 5: Implement TimeNavigator.vue**

```vue
<!-- src/components/common/TimeNavigator.vue -->
<template>
  <div class="time-nav">
    <el-button :icon="ArrowLeft" circle size="small" @click="goPrev" :disabled="!canGoPrev" />
    <span class="time-date">{{ displayDate }}</span>
    <span class="time-sep">│</span>
    <div class="time-periods">
      <span
        v-for="p in periods"
        :key="p.value"
        class="period-tab"
        :class="{ active: modelValue === p.value }"
        @click="$emit('update:modelValue', p.value)"
      >{{ p.label }}</span>
    </div>
    <el-button :icon="ArrowRight" circle size="small" @click="goNext" :disabled="!canGoNext" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'

const props = defineProps<{
  modelValue: string
  date: string
  canGoPrev?: boolean
  canGoNext?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'prev': []
  'next': []
}>()

const periods = [
  { label: '早读', value: 'arrival' },
  { label: '第1节', value: 'period_1' },
  { label: '第2节', value: 'period_2' },
  { label: '第3节', value: 'period_3' },
  { label: '第4节', value: 'period_4' },
  { label: '第5节', value: 'period_5' },
  { label: '第6节', value: 'period_6' },
  { label: '第7节', value: 'period_7' },
  { label: '第8节', value: 'period_8' },
  { label: '课间操', value: 'recess' },
  { label: '午休', value: 'lunch' },
  { label: '课外', value: 'afterclass' },
]

const displayDate = computed(() => {
  if (!props.date) return '—'
  const d = new Date(props.date)
  const weekdays = ['日', '一', '二', '三', '四', '五', '六']
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} 周${weekdays[d.getDay()]}`
})

function goPrev() { emit('prev') }
function goNext() { emit('next') }
</script>

<style scoped>
.time-nav {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-bottom: var(--space-6);
  flex-wrap: wrap;
}

.time-date {
  font-weight: 600;
  font-size: var(--text-base);
  white-space: nowrap;
}

.time-sep {
  color: var(--color-border);
}

.time-periods {
  display: flex;
  gap: 2px;
  overflow-x: auto;
  flex: 1;
}

.period-tab {
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-xs);
  border-radius: var(--radius-sm);
  cursor: pointer;
  white-space: nowrap;
  color: var(--color-muted-fg);
  border: 1px solid transparent;
}

.period-tab:hover {
  background: var(--color-muted);
}

.period-tab.active {
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-weight: 500;
}
</style>
```

- [ ] **Step 6: Run tests**

```bash
cd emotion-frontend && npx vitest run
```

Expected: 4 tests PASS (3 alert store + 1-2 KpiCard).

- [ ] **Step 7: Commit**

```bash
git add emotion-frontend/src/components/common/ emotion-frontend/src/__tests__/components/
git commit -m "feat: KpiCard, KpiCardRow, TimeNavigator common components"
```

---

### Task 7: ECharts 图表组件 (5个可复用图表)

**Files:**
- Create: `emotion-frontend/src/components/charts/EmotionTrendChart.vue`
- Create: `emotion-frontend/src/components/charts/EmotionTimelineChart.vue`
- Create: `emotion-frontend/src/components/charts/GradeComparisonChart.vue`
- Create: `emotion-frontend/src/components/charts/EmotionPieChart.vue`
- Create: `emotion-frontend/src/components/charts/PeriodBarChart.vue`

- [ ] **Step 1: Create EmotionTrendChart.vue (多线折线图 — 校级大盘 & 个人档案)**

```vue
<!-- src/components/charts/EmotionTrendChart.vue -->
<template>
  <div class="chart-wrapper">
    <div ref="chartRef" class="chart-container" />
    <div v-if="!data.length" class="chart-empty">暂无数据</div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, DataZoomComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, CanvasRenderer])

const props = defineProps<{
  data: { date: string; [key: string]: number | string }[]
  series: { name: string; key: string; color: string }[]
  title?: string
}>()

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

function initChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    window.addEventListener('resize', handleResize)
  }
  const option: echarts.EChartsOption = {
    tooltip: { trigger: 'axis' },
    legend: { data: props.series.map(s => s.name), bottom: 0, textStyle: { fontSize: 12 } },
    grid: { left: '3%', right: '4%', bottom: '12%', top: props.title ? '40px' : '10px', containLabel: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider', bottom: 30 }],
    xAxis: { type: 'category', data: props.data.map(d => d.date), axisLabel: { fontSize: 11 } },
    yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    series: props.series.map(s => ({
      name: s.name,
      type: 'line' as const,
      data: props.data.map(d => d[s.key] as number),
      smooth: true,
      lineStyle: { color: s.color, width: 2 },
      itemStyle: { color: s.color },
      symbol: 'circle',
      symbolSize: 4,
    })),
  }
  chart.setOption(option)
}

function handleResize() {
  chart?.resize()
}

watch(() => props.data, initChart, { deep: true })

onMounted(initChart)
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
})
</script>

<style scoped>
.chart-wrapper { position: relative; }
.chart-container { width: 100%; height: 320px; }
.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-muted-fg);
}
</style>
```

- [ ] **Step 2: Create EmotionTimelineChart.vue (堆叠面积图 — 班级看板)**

```vue
<!-- src/components/charts/EmotionTimelineChart.vue -->
<template>
  <div class="chart-wrapper">
    <div ref="chartRef" class="chart-container" />
    <div v-if="!data.length" class="chart-empty">暂无数据</div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent, DataZoomComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, CanvasRenderer])

const props = defineProps<{
  data: { time: string; happy: number; neutral: number; sad: number; angry: number; surprise: number; fear: number; disgust: number }[]
}>()

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

const seriesDef = [
  { name: '快乐', key: 'happy', color: '#22C55E' },
  { name: '惊喜', key: 'surprise', color: '#F59E0B' },
  { name: '中性', key: 'neutral', color: '#64748B' },
  { name: '悲伤', key: 'sad', color: '#F97316' },
  { name: '愤怒', key: 'angry', color: '#DC2626' },
  { name: '恐惧', key: 'fear', color: '#7C3AED' },
  { name: '厌恶', key: 'disgust', color: '#374151' },
]

function initChart() {
  if (!chartRef.value || !props.data.length) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    window.addEventListener('resize', () => chart?.resize())
  }
  chart.setOption({
    tooltip: { trigger: 'axis', valueFormatter: (v: unknown) => `${(v as number).toFixed(1)}%` },
    legend: { data: seriesDef.map(s => s.name), bottom: 0, textStyle: { fontSize: 11 } },
    grid: { left: '3%', right: '4%', bottom: '12%', top: 10, containLabel: true },
    dataZoom: [{ type: 'inside' }],
    xAxis: { type: 'category', data: props.data.map(d => d.time), axisLabel: { fontSize: 10, rotate: 45 } },
    yAxis: { type: 'value', max: 100 },
    series: seriesDef.map(s => ({
      name: s.name,
      type: 'line' as const,
      data: props.data.map(d => (d as any)[s.key] as number),
      stack: 'total',
      areaStyle: {},
      lineStyle: { color: s.color, width: 1 },
      itemStyle: { color: s.color },
      symbol: 'none',
      emphasis: { focus: 'series' as const },
    })),
  })
}

watch(() => props.data, initChart, { deep: true })
onMounted(initChart)
onBeforeUnmount(() => { chart?.dispose() })
</script>

<style scoped>
.chart-wrapper { position: relative; }
.chart-container { width: 100%; height: 300px; }
.chart-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--color-muted-fg); }
</style>
```

- [ ] **Step 3: Create GradeComparisonChart, EmotionPieChart, PeriodBarChart**

```vue
<!-- src/components/charts/GradeComparisonChart.vue -->
<template>
  <div class="chart-wrapper">
    <div ref="chartRef" class="chart-container" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  data: { name: string; value: number; classes?: { name: string; value: number }[] }[]
}>()

const emit = defineEmits<{ selectGrade: [index: number] }>()

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

function initChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    window.addEventListener('resize', () => chart?.resize())
    chart.on('click', (params: any) => {
      if (params.componentType === 'series') emit('selectGrade', params.dataIndex)
    })
  }
  chart.setOption({
    tooltip: { trigger: 'axis', valueFormatter: (v: unknown) => `${(v as number)}%` },
    grid: { left: '3%', right: '8%', top: 10, bottom: 10, containLabel: true },
    xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    yAxis: { type: 'category', data: props.data.map(d => d.name) },
    series: [{
      type: 'bar',
      data: props.data.map(d => ({
        value: d.value,
        itemStyle: { color: d.value >= 70 ? '#22C55E' : d.value >= 50 ? '#F59E0B' : '#DC2626' },
      })),
      barMaxWidth: 32,
      label: { show: true, position: 'right', formatter: '{c}%' },
    }],
  })
}

watch(() => props.data, initChart, { deep: true })
onMounted(initChart)
onBeforeUnmount(() => chart?.dispose())
</script>

<style scoped>
.chart-container { width: 100%; height: 250px; }
</style>
```

```vue
<!-- src/components/charts/EmotionPieChart.vue -->
<template>
  <div class="chart-wrapper">
    <div ref="chartRef" class="chart-container" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{
  data: { name: string; value: number; color: string }[]
}>()

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

function initChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    window.addEventListener('resize', () => chart?.resize())
  }
  chart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c}%' },
    legend: { bottom: 0, textStyle: { fontSize: 11 } },
    series: [{
      type: 'pie',
      radius: ['45%', '70%'],
      avoidLabelOverlap: false,
      itemStyle: { borderRadius: 4, borderColor: '#fff', borderWidth: 2 },
      label: { show: true, formatter: '{b}\n{d}%', fontSize: 11 },
      data: props.data.map(d => ({ name: d.name, value: d.value, itemStyle: { color: d.color } })),
    }],
  })
}

watch(() => props.data, initChart, { deep: true })
onMounted(initChart)
onBeforeUnmount(() => chart?.dispose())
</script>

<style scoped>
.chart-container { width: 100%; height: 280px; }
</style>
```

```vue
<!-- src/components/charts/PeriodBarChart.vue -->
<template>
  <div class="chart-wrapper">
    <div ref="chartRef" class="chart-container" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  data: { period: string; value: number }[]
}>()

const chartRef = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

function initChart() {
  if (!chartRef.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    window.addEventListener('resize', () => chart?.resize())
  }
  chart.setOption({
    tooltip: { trigger: 'axis', valueFormatter: (v: unknown) => `${(v as number)}` },
    grid: { left: '3%', right: '5%', top: 10, bottom: 10, containLabel: true },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: props.data.map(d => d.period) },
    series: [{
      type: 'bar',
      data: props.data.map(d => ({
        value: d.value,
        itemStyle: { color: d.value >= 70 ? '#22C55E' : d.value >= 50 ? '#F59E0B' : '#DC2626' },
      })),
      barMaxWidth: 20,
    }],
  })
}

watch(() => props.data, initChart, { deep: true })
onMounted(initChart)
onBeforeUnmount(() => chart?.dispose())
</script>

<style scoped>
.chart-container { width: 100%; height: 280px; }
</style>
```

- [ ] **Step 4: Verify charts compile**

```bash
cd emotion-frontend && npx vue-tsc --noEmit
```

Expected: No type errors.

- [ ] **Step 5: Commit**

```bash
git add emotion-frontend/src/components/charts/
git commit -m "feat: 5 reusable ECharts chart components (trend, timeline, comparison, pie, period)"
```

---

### Task 8: 校级大盘页面

**Files:**
- Create: `emotion-frontend/src/views/SchoolOverview.vue`

- [ ] **Step 1: Create SchoolOverview.vue**

```vue
<!-- src/views/SchoolOverview.vue -->
<template>
  <div class="page" v-loading="store.loading">
    <!-- Time filter -->
    <div class="filter-bar">
      <el-radio-group v-model="period" size="small" @change="loadData">
        <el-radio-button value="week">上周</el-radio-button>
        <el-radio-button value="this_week">本周</el-radio-button>
        <el-radio-button value="month">本月</el-radio-button>
        <el-radio-button value="semester">本学期</el-radio-button>
      </el-radio-group>
      <span class="filter-date">2026-05-27 周二</span>
      <el-button size="small" @click="exportReport">导出报表</el-button>
    </div>

    <!-- KPIs -->
    <KpiCardRow v-if="store.overviewData" :kpis="store.overviewData.kpis" />

    <!-- Charts row -->
    <div class="chart-row" v-if="store.overviewData">
      <div class="chart-left">
        <h3 class="section-title">各年级情绪健康度对比</h3>
        <GradeComparisonChart
          :data="store.overviewData.gradeComparison"
          @selectGrade="handleSelectGrade"
        />
      </div>
      <div class="chart-right">
        <h3 class="section-title">异常情绪率排行</h3>
        <div class="ranking-list">
          <div
            v-for="(item, i) in store.overviewData.alertRanking"
            :key="i"
            class="ranking-item"
            @click="goToClass(item.className)"
          >
            <span class="rank-num">{{ i + 1 }}</span>
            <span class="rank-name">{{ item.className }}</span>
            <span class="rank-bar"><span class="rank-fill" :style="{ width: item.rate * 10 + 'px' }" /></span>
            <span class="rank-val">{{ item.rate }}%</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Trend chart -->
    <div v-if="store.overviewData" style="margin-bottom: var(--space-6)">
      <h3 class="section-title">全校情绪健康度趋势</h3>
      <EmotionTrendChart
        :data="trendData"
        :series="[{ name: '情绪健康度', key: 'value', color: '#1E40AF' }]"
      />
    </div>

    <!-- Cross-class alerts -->
    <div v-if="store.overviewData">
      <div class="section-header">
        <h3 class="section-title">⚠️ 跨班级预警汇总</h3>
        <el-button size="small" text @click="markAllRead">全部已读</el-button>
      </div>
      <div class="alert-list">
        <div
          v-for="alert in store.overviewData.crossClassAlerts"
          :key="alert.id"
          class="alert-row"
          :class="{ acknowledged: alert.acknowledged }"
        >
          <span :class="'severity-dot severity-' + alert.severity" />
          <span class="alert-student">{{ alert.studentName }}</span>
          <span class="alert-class">· {{ alert.className }}</span>
          <span class="alert-msg">· {{ alert.message }}</span>
          <span class="alert-time">{{ alert.timestamp }}</span>
          <el-button size="small" text type="primary" @click="goToStudent(alert.studentId)">查看</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useSchoolStore } from '@/stores/useSchoolStore'
import { useAlertStore } from '@/stores/useAlertStore'
import { useNavigationStore } from '@/stores/useNavigationStore'
import KpiCardRow from '@/components/common/KpiCardRow.vue'
import GradeComparisonChart from '@/components/charts/GradeComparisonChart.vue'
import EmotionTrendChart from '@/components/charts/EmotionTrendChart.vue'

const router = useRouter()
const store = useSchoolStore()
const alertStore = useAlertStore()
const nav = useNavigationStore()
const period = ref('this_week')

const trendData = computed(() => {
  if (!store.overviewData) return []
  return store.overviewData.trendData.map(d => ({
    date: d.date,
    value: d.value,
  }))
})

function loadData() {
  store.loadOverview({ period: period.value })
}

function handleSelectGrade(index: number) {
  // Expand grade to show class breakdown
  console.log('Selected grade index:', index)
}

function goToClass(className: string) {
  // Navigate to class dashboard
  router.push('/class/1/dashboard')
}

function goToStudent(studentId: number) {
  router.push(`/student/${studentId}/profile`)
}

function markAllRead() {
  alertStore.markAllRead()
}

function exportReport() {
  // Trigger CSV/PDF export
  console.log('Export report')
}

onMounted(() => {
  nav.setBreadcrumbs([{ label: '校级' }])
  nav.setActiveNav('school')
  loadData()
})
</script>

<style scoped>
.page { padding: 0; }
.filter-bar {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}
.filter-date { font-size: var(--text-sm); color: var(--color-muted-fg); }

.section-title { font-size: var(--text-base); font-weight: 600; margin-bottom: var(--space-3); }
.section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-3); }

.chart-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-6);
  margin-bottom: var(--space-6);
}

.ranking-list { display: flex; flex-direction: column; gap: var(--space-2); }
.ranking-item {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.ranking-item:hover { background: var(--color-muted); }
.rank-num { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--color-muted-fg); width: 20px; }
.rank-name { font-size: var(--text-sm); min-width: 80px; }
.rank-bar { flex: 1; height: 8px; background: var(--color-muted); border-radius: 4px; overflow: hidden; }
.rank-fill { display: block; height: 100%; background: var(--color-primary); border-radius: 4px; }
.rank-val { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--color-muted-fg); }

.alert-list { display: flex; flex-direction: column; }
.alert-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-border);
}
.alert-row.acknowledged { opacity: 0.5; text-decoration: line-through; }
.severity-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.severity-high { background: var(--color-destructive); }
.severity-medium { background: var(--color-accent); }
.severity-low { background: var(--emotion-happy); }
.alert-student { font-weight: 500; }
.alert-class { color: var(--color-muted-fg); font-size: var(--text-sm); }
.alert-msg { flex: 1; font-size: var(--text-sm); }
.alert-time { color: var(--color-muted-fg); font-size: var(--text-xs); white-space: nowrap; }
</style>
```

- [ ] **Step 2: Verify page compiles**

```bash
cd emotion-frontend && npx vue-tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
git add emotion-frontend/src/views/SchoolOverview.vue
git commit -m "feat: SchoolOverview page with KPIs, charts, alert ranking"
```

---

### Task 9: 班级情绪看板页面

**Files:**
- Create: `emotion-frontend/src/views/ClassDashboard.vue`

- [ ] **Step 1: Create ClassDashboard.vue**

```vue
<!-- src/views/ClassDashboard.vue -->
<template>
  <div class="page" v-loading="store.loading">
    <!-- Time Navigator -->
    <TimeNavigator
      v-model="currentPeriod"
      :date="store.currentDate"
      @prev="prevDay"
      @next="nextDay"
    />

    <!-- KPIs -->
    <KpiCardRow v-if="store.dashboardData" :kpis="store.dashboardData.kpis" />

    <!-- Emotion Timeline Chart -->
    <div v-if="store.dashboardData" style="margin-bottom: var(--space-6)">
      <h3 class="section-title">📈 课堂情绪时间线</h3>
      <EmotionTimelineChart :data="store.dashboardData.timelineData" />
    </div>

    <!-- Student Table -->
    <div v-if="store.dashboardData" style="margin-bottom: var(--space-6)">
      <div class="section-header">
        <h3 class="section-title">📋 学生表情详情</h3>
        <el-input
          v-model="searchQuery"
          placeholder="搜索学号/姓名"
          size="small"
          clearable
          style="width: 200px"
        />
      </div>
      <el-table
        :data="filteredStudents"
        stripe
        size="small"
        @row-click="goToStudent"
        style="cursor: pointer"
      >
        <el-table-column prop="name" label="姓名" width="80" />
        <el-table-column prop="studentNo" label="学号" width="110" />
        <el-table-column label="主导表情" width="120">
          <template #default="{ row }">
            <span>{{ emotionLabel(row.dominantEmotion) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="快乐" width="70">
          <template #default="{ row }">{{ row.happy }}%</template>
        </el-table-column>
        <el-table-column label="中性" width="70">
          <template #default="{ row }">{{ row.neutral }}%</template>
        </el-table-column>
        <el-table-column label="悲伤" width="70">
          <template #default="{ row }">{{ row.sad }}%</template>
        </el-table-column>
        <el-table-column label="愤怒" width="70">
          <template #default="{ row }">{{ row.angry }}%</template>
        </el-table-column>
        <el-table-column label="参与度" width="120">
          <template #default="{ row }">
            <el-progress :percentage="row.engagement" :stroke-width="6" :show-text="false" />
            <span style="font-size:12px;color:var(--color-muted-fg);margin-left:4px">{{ row.engagement }}%</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="60" fixed="right">
          <template #default>
            <el-button size="small" text>👤→</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="text-align:center;margin-top:12px">
        <el-pagination
          v-model:current-page="currentPage"
          :total="store.dashboardData.totalPages * 20"
          :page-size="20"
          layout="prev, pager, next"
          small
        />
      </div>
    </div>

    <!-- Bottom: heatmap preview + focus students -->
    <div class="bottom-row" v-if="store.dashboardData">
      <div class="bottom-left">
        <h3 class="section-title">🪑 座位热力图</h3>
        <div class="heatmap-preview">
          <!-- Simplified 7×4 grid preview -->
          <div v-for="r in 4" :key="r" class="heatmap-row">
            <div v-for="c in 7" :key="c" class="heatmap-cell" :class="randomCellClass()" />
          </div>
        </div>
        <el-button size="small" text type="primary" @click="$router.push(`/class/${classId}/heatmap`)">
          展开完整热力图 →
        </el-button>
      </div>
      <div class="bottom-right">
        <h3 class="section-title">⚠️ 本班重点关注</h3>
        <div class="focus-list">
          <div v-for="student in alertStudents" :key="student.id" class="focus-card">
            <span class="severity-dot severity-high" />
            <div>
              <strong>{{ student.name }}</strong>
              <p style="font-size:12px;color:var(--color-muted-fg)">连续{{ student.consecutiveSections }}节{{ emotionLabel(student.dominantEmotion) }}</p>
            </div>
            <el-button size="small" @click="goToStudent(student)">查看档案</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useClassStore } from '@/stores/useClassStore'
import { useNavigationStore } from '@/stores/useNavigationStore'
import { useAlertStore } from '@/stores/useAlertStore'
import { connectClassSocket, disconnectClassSocket } from '@/api/websocket'
import type { StudentRow, EmotionType, WsEmotionUpdate } from '@/types'
import KpiCardRow from '@/components/common/KpiCardRow.vue'
import TimeNavigator from '@/components/common/TimeNavigator.vue'
import EmotionTimelineChart from '@/components/charts/EmotionTimelineChart.vue'

const route = useRoute()
const router = useRouter()
const store = useClassStore()
const nav = useNavigationStore()
const alertStore = useAlertStore()

const classId = computed(() => Number(route.params.classId))
const currentPeriod = ref('period_3')
const currentPage = ref(1)
const searchQuery = ref('')
const currentDate = ref('2026-05-26')

const filteredStudents = computed(() => {
  if (!store.dashboardData) return []
  let list = [...store.dashboardData.students]
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    list = list.filter(s => s.name.includes(q) || s.studentNo.includes(q))
  }
  // Alert students always on top
  list.sort((a, b) => (b.isAlert ? 1 : 0) - (a.isAlert ? 1 : 0))
  return list
})

const alertStudents = computed(() => {
  if (!store.dashboardData) return []
  return store.dashboardData.students
    .filter(s => s.isAlert)
    .map(s => ({ ...s, consecutiveSections: 3 })) // Mock
})

function loadData() {
  store.loadDashboard(classId.value, {
    date: currentDate.value,
    period_label: currentPeriod.value,
  })
}

function prevDay() {
  const d = new Date(currentDate.value)
  d.setDate(d.getDate() - 1)
  currentDate.value = d.toISOString().split('T')[0]
  loadData()
}

function nextDay() {
  const d = new Date(currentDate.value)
  d.setDate(d.getDate() + 1)
  currentDate.value = d.toISOString().split('T')[0]
  loadData()
}

function goToStudent(row: StudentRow) {
  router.push(`/student/${row.id}/profile`)
}

function emotionLabel(e: EmotionType): string {
  const map: Record<EmotionType, string> = {
    happy: '😊 快乐', sad: '😢 悲伤', angry: '😠 愤怒',
    surprise: '😲 惊讶', fear: '😨 恐惧', disgust: '😖 厌恶', neutral: '😐 中性',
  }
  return map[e] || e
}

function handleWsUpdate(data: WsEmotionUpdate) {
  store.updateFromWs(data.updates)
}

function randomCellClass(): string {
  const classes = ['cell-high', 'cell-high', 'cell-high', 'cell-mid', 'cell-mid', 'cell-low', 'cell-absent']
  return classes[Math.floor(Math.random() * classes.length)]
}

watch(currentPeriod, loadData)
watch(classId, loadData)

onMounted(() => {
  nav.setBreadcrumbs([
    { label: '校级', to: '/school/overview' },
    { label: '初一', to: '/school/overview' },
    { label: `初一3班` },
  ])
  nav.setActiveNav('class')
  connectClassSocket(classId.value, handleWsUpdate)
  loadData()
})

onBeforeUnmount(() => {
  disconnectClassSocket()
})
</script>

<style scoped>
.page { padding: 0; }

.section-title { font-size: var(--text-base); font-weight: 600; margin-bottom: var(--space-3); }
.section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-3); }

.bottom-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-6);
}

.heatmap-preview { display: flex; flex-direction: column; gap: 4px; margin-bottom: var(--space-2); }
.heatmap-row { display: flex; gap: 4px; }
.heatmap-cell { width: 24px; height: 24px; border-radius: 4px; }
.cell-high { background: #22C55E; }
.cell-mid { background: #F59E0B; }
.cell-low { background: #F97316; }
.cell-absent { background: #DC2626; }

.focus-list { display: flex; flex-direction: column; gap: var(--space-3); }
.focus-card {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-card);
}

.severity-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.severity-high { background: var(--color-destructive); }
</style>
```

- [ ] **Step 2: Verify page compiles**

```bash
cd emotion-frontend && npx vue-tsc --noEmit
```

- [ ] **Step 3: Commit**

```bash
git add emotion-frontend/src/views/ClassDashboard.vue
git commit -m "feat: ClassDashboard page with time nav, chart, table, heatmap preview"
```

---

### Task 10: 座位热力图 + 个人情绪档案页面

**Files:**
- Create: `emotion-frontend/src/views/SeatHeatmap.vue`
- Create: `emotion-frontend/src/views/StudentProfile.vue`
- Create: `emotion-frontend/src/components/student/StudentPopover.vue`
- Create: `emotion-frontend/src/components/student/AlertTimeline.vue`
- Create: `emotion-frontend/src/components/student/InterventionLog.vue`

- [ ] **Step 1: Create SeatHeatmap.vue**

```vue
<!-- src/views/SeatHeatmap.vue -->
<template>
  <div class="page" v-loading="store.loading">
    <!-- Time selector -->
    <div class="filter-bar">
      <span class="filter-date">{{ currentDate }}</span>
      <el-select v-model="currentPeriod" size="small" @change="loadData">
        <el-option v-for="p in periods" :key="p.value" :label="p.label" :value="p.value" />
      </el-select>
      <span class="course-tag">课程: 数学</span>
    </div>

    <!-- Seat Matrix -->
    <div class="seat-area" v-if="store.heatmapData">
      <div class="podium">讲 台 🧑‍🏫</div>
      <div class="seat-matrix">
        <div v-for="row in store.heatmapData.rows" :key="row" class="seat-row">
          <div v-for="col in store.heatmapData.cols" :key="col" class="seat-cell" :class="seatClass(row, col)"
               @click="goToStudent(row, col)" @mouseenter="showPopover(row, col)" @mouseleave="hidePopover">
            <template v-if="getSeat(row, col)">
              <span class="seat-name">{{ getSeat(row, col)?.studentName }}</span>
              <span class="seat-score">{{ getSeat(row, col)?.engagement ?? '—' }}%</span>
            </template>
          </div>
        </div>
      </div>

      <!-- Popover -->
      <Teleport to="body">
        <div v-if="popover.seat" class="student-popover" :style="{ top: popover.y + 'px', left: popover.x + 'px' }">
          <div class="popover-header">
            <strong>{{ popover.seat.studentName }}</strong> · {{ popover.seat.studentNo }}
          </div>
          <div class="popover-body">
            <p>😊 参与度: {{ popover.seat.engagement ?? '—' }}%</p>
            <div class="popover-sparkline">╱╲╱╲</div>
          </div>
          <el-button size="small" text type="primary" @click="goToStudent(row, col)">查看完整档案 →</el-button>
        </div>
      </Teleport>
    </div>

    <!-- Legend -->
    <div class="legend" v-if="store.heatmapData">
      <span class="legend-item"><span class="dot" style="background:#22C55E" /> 高参与 ≥70%</span>
      <span class="legend-item"><span class="dot" style="background:#F59E0B" /> 中等 40-69%</span>
      <span class="legend-item"><span class="dot" style="background:#F97316" /> 低 &lt;40%</span>
      <span class="legend-item"><span class="dot" style="background:#DC2626" /> 缺席</span>
    </div>

    <!-- Distribution + Alerts -->
    <div class="bottom-row" v-if="store.heatmapData">
      <div class="bottom-left">
        <h3>📊 参与度分布</h3>
        <div v-for="d in store.heatmapData.distribution" :key="d.label" class="dist-item">
          <span class="dist-label">{{ d.label }}</span>
          <span class="dist-bar"><span class="dist-fill" :style="{ width: d.pct + '%' }" /></span>
          <span class="dist-val">{{ d.count }}人 ({{ d.pct }}%)</span>
        </div>
      </div>
      <div class="bottom-right">
        <h3>🔍 连续低参与提醒</h3>
        <div v-for="(alert, i) in store.heatmapData.lowEngagementAlerts" :key="i" class="low-alert">
          <span class="severity-dot severity-high" />
          {{ alert.studentName }} · {{ alert.seatInfo }} · {{ alert.desc }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useClassStore } from '@/stores/useClassStore'
import { useNavigationStore } from '@/stores/useNavigationStore'
import type { SeatData } from '@/types'

const route = useRoute()
const router = useRouter()
const store = useClassStore()
const nav = useNavigationStore()

const classId = computed(() => Number(route.params.classId))
const currentPeriod = ref('period_3')
const currentDate = ref('2026-05-26')
const popover = ref<{ seat: SeatData | null; x: number; y: number; row: number; col: number }>({
  seat: null, x: 0, y: 0, row: 0, col: 0,
})

const periods = [
  { label: '早读', value: 'arrival' }, { label: '第1节', value: 'period_1' }, { label: '第2节', value: 'period_2' },
  { label: '第3节', value: 'period_3' }, { label: '第4节', value: 'period_4' }, { label: '第5节', value: 'period_5' },
  { label: '第6节', value: 'period_6' }, { label: '第7节', value: 'period_7' }, { label: '第8节', value: 'period_8' },
  { label: '课间操', value: 'recess' }, { label: '午休', value: 'lunch' }, { label: '课外', value: 'afterclass' },
]

function loadData() {
  store.loadHeatmap(classId.value, { date: currentDate.value, period_label: currentPeriod.value })
}

function getSeat(row: number, col: number): SeatData | undefined {
  return store.heatmapData?.seats.find(s => s.row === row && s.col === col)
}

function seatClass(row: number, col: number): string {
  const seat = getSeat(row, col)
  if (!seat || seat.isEmpty) return 'cell-empty'
  if (seat.isAbsent) return 'cell-absent'
  if (seat.engagement === null) return 'cell-empty'
  if (seat.engagement >= 70) return 'cell-high'
  if (seat.engagement >= 40) return 'cell-mid'
  return 'cell-low'
}

function showPopover(row: number, col: number) {
  const seat = getSeat(row, col)
  if (!seat || seat.isEmpty) return
  popover.value = { seat, x: 0, y: 0, row, col }
}

function hidePopover() {
  popover.value.seat = null
}

function goToStudent(_row: number, _col: number) {
  const seat = getSeat(_row, _col)
  if (seat?.studentId) router.push(`/student/${seat.studentId}/profile`)
}

onMounted(() => {
  nav.setBreadcrumbs([
    { label: '校级', to: '/school/overview' },
    { label: '初一', to: '/school/overview' },
    { label: `初一3班`, to: `/class/${classId.value}/dashboard` },
    { label: '座位热力图' },
  ])
  loadData()
})
</script>

<style scoped>
.page { padding: 0; }
.filter-bar { display: flex; align-items: center; gap: var(--space-4); margin-bottom: var(--space-6); }
.filter-date { font-weight: 600; }
.course-tag { font-size: var(--text-xs); color: var(--color-muted-fg); }

.seat-area { margin-bottom: var(--space-6); }
.podium {
  text-align: center;
  padding: var(--space-2);
  background: var(--color-muted);
  border-radius: var(--radius-md);
  margin-bottom: var(--space-4);
  font-weight: 500;
}

.seat-matrix { display: flex; flex-direction: column; gap: 4px; }
.seat-row { display: flex; gap: 4px; justify-content: center; }

.seat-cell {
  width: 80px;
  height: 60px;
  border-radius: var(--radius-sm);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: transform 0.15s;
  font-size: var(--text-xs);
}
.seat-cell:hover { transform: scale(1.1); z-index: 10; }
.cell-high { background: #DCFCE7; border: 1px solid #22C55E; }
.cell-mid { background: #FEF3C7; border: 1px solid #F59E0B; }
.cell-low { background: #FFEDD5; border: 1px solid #F97316; }
.cell-absent { background: #FEE2E2; border: 1px solid #DC2626; }
.cell-empty { background: transparent; border: 1px dashed var(--color-border); }

.seat-name { font-weight: 500; font-size: 11px; }
.seat-score { font-family: var(--font-mono); font-size: 11px; color: var(--color-muted-fg); }

.student-popover {
  position: fixed;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-popover);
  padding: var(--space-3);
  z-index: 2000;
  min-width: 180px;
}
.popover-header { margin-bottom: var(--space-2); }
.popover-body { font-size: var(--text-xs); margin-bottom: var(--space-2); }
.popover-sparkline { font-family: var(--font-mono); font-size: 18px; letter-spacing: 2px; color: var(--color-muted-fg); }

.legend { display: flex; gap: var(--space-4); margin-bottom: var(--space-6); flex-wrap: wrap; }
.legend-item { display: flex; align-items: center; gap: var(--space-1); font-size: var(--text-xs); }
.dot { width: 10px; height: 10px; border-radius: 2px; }

.bottom-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-6); }
.dist-item { display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-2); font-size: var(--text-sm); }
.dist-label { width: 80px; }
.dist-bar { flex: 1; height: 14px; background: var(--color-muted); border-radius: 7px; overflow: hidden; }
.dist-fill { display: block; height: 100%; background: var(--color-primary); border-radius: 7px; }
.dist-val { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--color-muted-fg); }

.low-alert {
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--color-border);
  font-size: var(--text-sm);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.severity-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.severity-high { background: var(--color-destructive); }
</style>
```

- [ ] **Step 2: Create StudentPopover, AlertTimeline, InterventionLog**

```vue
<!-- src/components/student/StudentPopover.vue -->
<template>
  <div class="popover" v-if="student" :style="{ top: y + 'px', left: x + 'px' }">
    <div><strong>{{ student.studentName }}</strong> · {{ student.studentNo }}</div>
    <div>参与度: {{ student.engagement ?? '—' }}%</div>
    <div class="sparkline">╱╲╱╲</div>
    <router-link :to="`/student/${student.studentId}/profile`">查看完整档案 →</router-link>
  </div>
</template>

<script setup lang="ts">
import type { SeatData } from '@/types'
defineProps<{ student: SeatData | null; x: number; y: number }>()
</script>

<style scoped>
.popover {
  position: fixed;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-popover);
  padding: var(--space-3);
  z-index: 2000;
  min-width: 180px;
  font-size: var(--text-xs);
}
.sparkline { font-family: var(--font-mono); font-size: 18px; letter-spacing: 2px; color: var(--color-muted-fg); margin: var(--space-1) 0; }
</style>
```

```vue
<!-- src/components/student/AlertTimeline.vue -->
<template>
  <div class="timeline">
    <div v-if="!items.length" class="empty">暂无异常事件</div>
    <div v-for="(item, i) in items" :key="i" class="timeline-item">
      <div class="timeline-dot" />
      <div v-if="i < items.length - 1" class="timeline-line" />
      <div class="timeline-content">
        <span class="time">{{ item.date }} {{ item.period }}</span>
        <p>{{ item.desc }}</p>
        <span class="trigger">触发值: {{ item.triggerValue }}%</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  items: { date: string; period: string; desc: string; triggerValue: number }[]
}>()
</script>

<style scoped>
.timeline { position: relative; padding-left: var(--space-6); }
.empty { color: var(--color-muted-fg); font-size: var(--text-sm); }
.timeline-item { position: relative; padding-bottom: var(--space-4); }
.timeline-dot {
  position: absolute; left: -20px; top: 4px;
  width: 10px; height: 10px; border-radius: 50%;
  background: var(--color-primary); border: 2px solid var(--color-card);
}
.timeline-line {
  position: absolute; left: -16px; top: 14px; bottom: 0;
  width: 2px; background: var(--color-border);
}
.timeline-content { font-size: var(--text-sm); }
.time { font-size: var(--text-xs); color: var(--color-muted-fg); }
.trigger { color: var(--color-accent); font-size: var(--text-xs); }
</style>
```

```vue
<!-- src/components/student/InterventionLog.vue -->
<template>
  <div>
    <div class="log-header">
      <h3>📝 干预记录</h3>
      <el-button size="small" type="primary" @click="$emit('add')">+ 记录干预</el-button>
    </div>
    <div v-if="!items.length" class="empty">暂无干预记录</div>
    <div v-for="item in items" :key="item.id" class="log-item">
      <div class="log-meta">
        <span class="log-date">{{ item.createdAt }}</span>
        <span class="log-type">{{ item.actionType }}</span>
        <span class="log-teacher">· {{ item.teacherName }}</span>
      </div>
      <p class="log-desc">{{ item.description }}</p>
      <p v-if="item.effect" class="log-effect">效果: {{ item.effect }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { InterventionRecord } from '@/types'
defineProps<{ items: InterventionRecord[] }>()
defineEmits(['add'])
</script>

<style scoped>
.log-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-4); }
.empty { color: var(--color-muted-fg); font-size: var(--text-sm); }
.log-item { padding: var(--space-3) 0; border-bottom: 1px solid var(--color-border); }
.log-meta { font-size: var(--text-xs); color: var(--color-muted-fg); margin-bottom: var(--space-1); }
.log-type { color: var(--color-secondary); font-weight: 500; }
.log-desc { font-size: var(--text-sm); }
.log-effect { font-size: var(--text-xs); color: var(--color-muted-fg); margin-top: var(--space-1); }
</style>
```

- [ ] **Step 3: Create StudentProfile.vue**

```vue
<!-- src/views/StudentProfile.vue -->
<template>
  <div class="page" v-loading="store.loading">
    <!-- Student header -->
    <div class="student-header" v-if="store.profileData">
      <div>
        <h2>👤 {{ store.profileData.studentName }} · {{ store.profileData.studentNo }} · {{ store.profileData.className }}</h2>
        <div class="tags">
          <el-tag v-for="tag in store.profileData.tags" :key="tag" size="small" :type="tagType(tag)">{{ tag }}</el-tag>
          <el-button size="small" text>+ 添加标记</el-button>
        </div>
      </div>
    </div>

    <!-- KPIs -->
    <KpiCardRow v-if="store.profileData" :kpis="store.profileData.kpis" />

    <!-- Trend chart with time granularity -->
    <div v-if="store.profileData" style="margin-bottom: var(--space-6)">
      <h3 class="section-title">📈 情绪变化趋势</h3>
      <el-radio-group v-model="trendGranularity" size="small" style="margin-bottom:8px">
        <el-radio-button value="day">日</el-radio-button>
        <el-radio-button value="week">周</el-radio-button>
        <el-radio-button value="month">月</el-radio-button>
        <el-radio-button value="semester">学期</el-radio-button>
      </el-radio-group>
      <EmotionTrendChart
        :data="trendChartData"
        :series="[
          { name: '😊 快乐', key: 'happy', color: '#22C55E' },
          { name: '😐 中性', key: 'neutral', color: '#64748B' },
          { name: '😢 悲伤', key: 'sad', color: '#F97316' },
          { name: '😠 愤怒', key: 'angry', color: '#DC2626' },
          { name: '😲 惊讶', key: 'surprise', color: '#F59E0B' },
          { name: '😨 恐惧', key: 'fear', color: '#7C3AED' },
          { name: '😖 厌恶', key: 'disgust', color: '#374151' },
        ]"
      />
    </div>

    <!-- Distribution + Period comparison -->
    <div class="chart-row" v-if="store.profileData">
      <div>
        <h3 class="section-title">🎭 表情分布 (本周)</h3>
        <EmotionPieChart :data="pieChartData" />
      </div>
      <div>
        <h3 class="section-title">📅 各时段情绪对比</h3>
        <PeriodBarChart :data="store.profileData.periodComparison" />
      </div>
    </div>

    <!-- Alert timeline -->
    <div v-if="store.profileData" style="margin-bottom: var(--space-6)">
      <h3 class="section-title">⚠️ 异常事件时间线</h3>
      <AlertTimeline :items="store.profileData.alertTimeline" />
    </div>

    <!-- Intervention log -->
    <div v-if="store.profileData">
      <InterventionLog :items="store.profileData.interventions" @add="showDrawer = true" />
    </div>

    <!-- Intervention Drawer -->
    <el-drawer v-model="showDrawer" title="记录干预" size="480px" direction="rtl">
      <el-form :model="interventionForm" label-position="top">
        <el-form-item label="干预类型" required>
          <el-select v-model="interventionForm.action_type" style="width:100%">
            <el-option label="个别谈话" value="talk" />
            <el-option label="家长沟通" value="parent" />
            <el-option label="心理辅导" value="counseling" />
            <el-option label="同伴互助" value="peer" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="干预日期">
          <el-date-picker v-model="interventionForm.date" type="date" style="width:100%" />
        </el-form-item>
        <el-form-item label="干预描述" required>
          <el-input v-model="interventionForm.description" type="textarea" :rows="4" placeholder="记录干预内容、学生反应等" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="saveIntervention">保存</el-button>
          <el-button @click="showDrawer = false">取消</el-button>
        </el-form-item>
      </el-form>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useStudentStore } from '@/stores/useStudentStore'
import { useNavigationStore } from '@/stores/useNavigationStore'
import { createIntervention } from '@/api/student'
import KpiCardRow from '@/components/common/KpiCardRow.vue'
import EmotionTrendChart from '@/components/charts/EmotionTrendChart.vue'
import EmotionPieChart from '@/components/charts/EmotionPieChart.vue'
import PeriodBarChart from '@/components/charts/PeriodBarChart.vue'
import AlertTimeline from '@/components/student/AlertTimeline.vue'
import InterventionLog from '@/components/student/InterventionLog.vue'

const route = useRoute()
const store = useStudentStore()
const nav = useNavigationStore()

const studentId = computed(() => Number(route.params.studentId))
const trendGranularity = ref('week')
const showDrawer = ref(false)

const interventionForm = ref({
  action_type: 'talk',
  date: new Date(),
  description: '',
})

const trendChartData = computed(() => {
  if (!store.profileData) return []
  return store.profileData.trendData.map(d => ({
    date: d.date,
    happy: (d as any).happy || 0,
    neutral: (d as any).neutral || 0,
    sad: (d as any).sad || 0,
    angry: (d as any).angry || 0,
    surprise: (d as any).surprise || 0,
    fear: (d as any).fear || 0,
    disgust: (d as any).disgust || 0,
  }))
})

const pieChartData = computed(() => {
  if (!store.profileData) return []
  const d = store.profileData.weekDistribution
  return [
    { name: '快乐', value: d.happy, color: '#22C55E' },
    { name: '中性', value: d.neutral, color: '#64748B' },
    { name: '悲伤', value: d.sad, color: '#F97316' },
    { name: '愤怒', value: d.angry, color: '#DC2626' },
    { name: '惊讶', value: d.surprise, color: '#F59E0B' },
    { name: '恐惧', value: d.fear, color: '#7C3AED' },
    { name: '厌恶', value: d.disgust, color: '#374151' },
  ].filter(item => item.value > 0)
})

function tagType(tag: string): string {
  if (tag.includes('情绪')) return 'danger'
  if (tag.includes('学业')) return 'warning'
  return 'info'
}

async function saveIntervention() {
  await createIntervention({
    student_id: studentId.value,
    action_type: interventionForm.value.action_type,
    description: interventionForm.value.description,
  })
  showDrawer.value = false
  store.loadProfile(studentId.value)
}

onMounted(() => {
  nav.setBreadcrumbs([
    { label: '校级', to: '/school/overview' },
    { label: '初一', to: '/school/overview' },
    { label: '初一3班', to: `/class/1/dashboard` },
    { label: '张三' },
  ])
  store.loadProfile(studentId.value)
})
</script>

<style scoped>
.page { padding: 0; }
.student-header { margin-bottom: var(--space-6); }
.tags { display: flex; align-items: center; gap: var(--space-2); margin-top: var(--space-2); }
.section-title { font-size: var(--text-base); font-weight: 600; margin-bottom: var(--space-3); }
.chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-6); margin-bottom: var(--space-6); }
</style>
```

- [ ] **Step 4: Verify all pages compile**

```bash
cd emotion-frontend && npx vue-tsc --noEmit
```

- [ ] **Step 5: Commit**

```bash
git add emotion-frontend/src/views/SeatHeatmap.vue emotion-frontend/src/views/StudentProfile.vue emotion-frontend/src/components/student/
git commit -m "feat: SeatHeatmap and StudentProfile pages with all sub-components"
```

---

### Task 11: WebSocket 集成 & Dev 验证

**Files:**
- Modify: `emotion-frontend/src/App.vue` (add global alert WS)
- Create: `emotion-frontend/src/__tests__/views/ClassDashboard.test.ts`

- [ ] **Step 1: Write component test for ClassDashboard**

```typescript
// src/__tests__/views/ClassDashboard.test.ts
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import ClassDashboard from '@/views/ClassDashboard.vue'

describe('ClassDashboard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders page title', () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [{ path: '/class/:classId/dashboard', component: {} }],
    })
    const wrapper = mount(ClassDashboard, {
      global: { plugins: [router] },
    })
    expect(wrapper.find('.page').exists()).toBe(true)
  })
})
```

- [ ] **Step 2: Run all tests**

```bash
cd emotion-frontend && npx vitest run
```

Expected: All tests pass.

- [ ] **Step 3: Add global WebSocket connection in App.vue**

```typescript
// Add to App.vue <script setup>
import { onMounted, onBeforeUnmount } from 'vue'
import { useAlertStore } from '@/stores/useAlertStore'
import { connectAlertSocket, disconnectAlertSocket } from '@/api/websocket'
import type { WsAlert } from '@/types'

const alertStore = useAlertStore()

function handleAlert(data: WsAlert) {
  alertStore.addAlert({
    id: data.alert_id,
    studentId: 0,
    studentName: data.student_name,
    className: data.class_name,
    type: 'sad',
    severity: data.severity,
    message: data.message,
    timestamp: data.timestamp,
    acknowledged: false,
  })
}

onMounted(() => {
  connectAlertSocket(handleAlert)
})

onBeforeUnmount(() => {
  disconnectAlertSocket()
})
```

The full App.vue should now be:

```vue
<!-- src/App.vue -->
<template>
  <AppLayout>
    <router-view v-slot="{ Component }">
      <transition name="fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </AppLayout>
</template>

<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import AppLayout from '@/components/layout/AppLayout.vue'
import { useAlertStore } from '@/stores/useAlertStore'
import { connectAlertSocket, disconnectAlertSocket } from '@/api/websocket'
import type { WsAlert } from '@/types'

const alertStore = useAlertStore()

function handleAlert(data: WsAlert) {
  alertStore.addAlert({
    id: data.alert_id,
    studentId: 0,
    studentName: data.student_name,
    className: data.class_name,
    type: 'sad',
    severity: data.severity,
    message: data.message,
    timestamp: data.timestamp,
    acknowledged: false,
  })
}

onMounted(() => { connectAlertSocket(handleAlert) })
onBeforeUnmount(() => { disconnectAlertSocket() })
</script>
```

- [ ] **Step 4: Final verification**

```bash
cd emotion-frontend
npm run test       # All tests pass
npx vue-tsc --noEmit  # No type errors
npm run build      # Production build succeeds
```

- [ ] **Step 5: Commit**

```bash
git add emotion-frontend/src/App.vue emotion-frontend/src/__tests__/
git commit -m "feat: global WebSocket alert connection and component tests"
```

---

## 完成标准

1. ✅ `npm run dev` — Vite 开发服务器正常启动
2. ✅ `npm run test` — 所有测试通过
3. ✅ `npx vue-tsc --noEmit` — 零类型错误
4. ✅ `npm run build` — 生产构建成功
5. ✅ 4个核心页面路由可达：`/school/overview`, `/class/:id/dashboard`, `/class/:id/heatmap`, `/student/:id/profile`
6. ✅ 全局框架：侧栏导航 + 顶栏预警 + 面包屑
7. ✅ 7种情绪的双通道编码（颜色+图标）在图表和表格中正确展示
8. ✅ WebSocket 连接管理支持断线重连
