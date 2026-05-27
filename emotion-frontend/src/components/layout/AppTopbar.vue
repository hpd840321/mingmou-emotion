<template>
  <header class="topbar">
    <div class="topbar-left">
      <span class="topbar-logo" @click="$router.push('/')">明眸</span>
      <span class="topbar-title">学生身心健康管理平台</span>
      <span v-if="auth.user" class="topbar-role">{{ roleLabel }}</span>
    </div>
    <div class="topbar-right">
      <AlertBadge :count="alertStore.unreadCount" @click="showAlertPanel = !showAlertPanel" />
      <UserMenu />
    </div>
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
    admin: '系统管理员', school_manager: '校级管理者', grade_leader: '年级组长',
    teacher: '班主任', counselor: '心理辅导老师', student: '学生', parent: '家长',
  }
  return labels[auth.user.role] || ''
})

function severityIcon(s: string) { return { high: '🔴', medium: '🟡', low: '🟢' }[s] || '⚪' }
function formatTime(ts: string) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}
function goToStudent(id: number) { router.push(`/student/${id}/profile`) }
</script>

<style scoped>
.topbar {
  height: var(--topbar-height); display: flex; align-items: center;
  justify-content: space-between; padding: 0 var(--space-4);
  background: var(--color-primary); color: var(--color-on-primary);
  position: relative; z-index: 100; flex-shrink: 0;
}
.topbar-left { display: flex; align-items: center; gap: var(--space-3); }
.topbar-logo { font-family: var(--font-mono); font-weight: 700; font-size: var(--text-lg); cursor: pointer; }
.topbar-title { font-size: var(--text-sm); opacity: 0.85; }
.topbar-role { font-size: var(--text-xs); background: rgba(255,255,255,0.15); padding: 2px 10px; border-radius: var(--radius-sm); }
.topbar-right { display: flex; align-items: center; gap: var(--space-4); }
.alert-panel {
  position: fixed; top: var(--topbar-height); right: 120px; width: 360px;
  max-height: 420px; overflow-y: auto; background: var(--color-card);
  border: 1px solid var(--color-border); border-radius: var(--radius-md);
  box-shadow: var(--shadow-popover); z-index: 2000; padding: var(--space-4);
}
.alert-overlay { position: fixed; inset: 0; z-index: 1999; }
.alert-item { display: flex; gap: var(--space-2); padding: var(--space-2) 0; border-bottom: 1px solid var(--color-border); cursor: pointer; }
.alert-item:hover { background: var(--color-muted); }
.alert-item p { font-size: var(--text-xs); color: var(--color-muted-fg); }
.alert-empty { text-align: center; color: var(--color-muted-fg); padding: var(--space-4); }
.alert-footer { text-align: center; padding-top: var(--space-2); }
</style>
