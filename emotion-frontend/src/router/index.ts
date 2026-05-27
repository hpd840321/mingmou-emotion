import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/school/overview' },
  {
    path: '/school/overview',
    name: 'SchoolOverview',
    component: () => import('@/views/SchoolOverview.vue'),
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
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/NotFound.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })
export default router
