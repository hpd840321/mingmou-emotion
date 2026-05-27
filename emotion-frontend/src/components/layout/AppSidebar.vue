<template>
  <aside class="sidebar">
    <nav class="sidebar-nav">
      <template v-for="(item, idx) in visibleItems" :key="'separator' in item ? 'sep-' + idx : item.key">
        <div v-if="'separator' in item" class="sidebar-separator" />
        <router-link v-else :to="item.to" class="sidebar-item" :class="{ active: isActive(item.key) }">
          <span class="sidebar-icon">{{ item.icon }}</span>
          <span class="sidebar-label">{{ item.label }}</span>
        </router-link>
      </template>
    </nav>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/useAuthStore'

const route = useRoute()
const auth = useAuthStore()

interface NavItem { key: string; label: string; icon: string; to: string; roles?: string[] }
const allItems: (NavItem | { separator: true })[] = [
  { key: 'school', label: '校级大盘', icon: '📊', to: '/school/overview', roles: ['admin', 'school_manager', 'grade_leader'] },
  { key: 'class', label: '班级看板', icon: '📋', to: '/class/1/dashboard' },
  { key: 'student', label: '个人档案', icon: '👤', to: '/student/1/profile' },
  { separator: true },
  { key: 'clusters', label: '人脸标注', icon: '🏷️', to: '/face-clusters', roles: ['admin', 'teacher'] },
  { key: 'alerts', label: '预警规则', icon: '⚠️', to: '/alerts', roles: ['admin', 'school_manager'] },
  { separator: true },
  { key: 'admin', label: '系统管理', icon: '⚙️', to: '/admin', roles: ['admin'] },
]

const visibleItems = computed(() => allItems.filter(item => {
  if ('separator' in item) return true
  if (!item.roles) return true
  return auth.hasRole(item.roles as any)
}))

function isActive(key: string): boolean {
  if (key === 'school') return route.path.startsWith('/school')
  if (key === 'class') return route.path.startsWith('/class')
  if (key === 'student') return route.path.startsWith('/student')
  return false
}
</script>

<style scoped>
.sidebar {
  width: var(--sidebar-width); background: var(--color-card);
  border-right: 1px solid var(--color-border); flex-shrink: 0;
  overflow-y: auto; padding-top: var(--space-2);
}
.sidebar-item {
  display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-3) var(--space-4); color: var(--color-muted-fg);
  text-decoration: none; font-size: var(--text-sm);
  border-left: 3px solid transparent; transition: all 0.15s ease-out;
}
.sidebar-item:hover { background: var(--color-muted); color: var(--color-fg); text-decoration: none; }
.sidebar-item.active { background: #EFF6FF; color: var(--color-primary); border-left-color: var(--color-primary); font-weight: 500; }
.sidebar-icon { font-size: 18px; }
.sidebar-separator { height: 1px; background: var(--color-border); margin: var(--space-2) var(--space-4); }
@media (max-width: 1023px) { .sidebar { width: var(--sidebar-collapsed); } .sidebar-label { display: none; } }
</style>
