import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/useAuthStore'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'Login', component: () => import('@/views/LoginPage.vue') },
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
  {
    path: '/class/:classId/replay',
    name: 'ClassroomReplay',
    component: () => import('@/views/ClassroomReplay.vue'),
    props: true,
  },
  {
    path: '/class/:classId/schedule',
    name: 'Schedule',
    component: () => import('@/views/SchedulePage.vue'),
    props: true,
  },
  {
    path: '/school-tree',
    name: 'SchoolTree',
    component: () => import('@/views/SchoolTree.vue'),
  },
  {
    path: '/face-clusters',
    name: 'FaceCluster',
    component: () => import('@/views/FaceClusterPage.vue'),
    meta: { roles: ['admin', 'teacher'] },
  },
  {
    path: '/alerts',
    name: 'AlertRules',
    component: () => import('@/views/AlertRulePage.vue'),
    meta: { roles: ['admin', 'school_manager'] },
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('@/views/AdminPage.vue'),
    meta: { roles: ['admin'] },
  },
  {
    path: '/admin/pipeline',
    name: 'PipelineMonitor',
    component: () => import('@/views/PipelineMonitor.vue'),
    meta: { roles: ['admin'] },
  },
  {
    path: '/student-base',
    name: 'StudentBase',
    component: () => import('@/views/StudentBasePage.vue'),
    meta: { roles: ['admin', 'school_manager', 'grade_leader'] },
  },
  {
    path: '/admin/cameras',
    name: 'CameraManage',
    component: () => import('@/views/CameraManagePage.vue'),
    meta: { roles: ['admin'] },
  },
  {
    path: '/admin/engines',
    name: 'EngineManage',
    component: () => import('@/views/EngineManagePage.vue'),
    meta: { roles: ['admin'] },
  },
  { path: '/:pathMatch(.*)*', name: 'NotFound', component: () => import('@/views/NotFound.vue') },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()

  if (to.path === '/login') {
    next()
    return
  }

  if (!auth.token) {
    next('/login')
    return
  }

  if (!auth.user) {
    const restored = await auth.restoreSession()
    if (!restored) {
      next('/login')
      return
    }
  }

  const requiredRoles = to.meta?.roles as string[] | undefined
  if (requiredRoles && !auth.hasRole(requiredRoles as any)) {
    next('/school/overview')
    return
  }

  next()
})

export default router
