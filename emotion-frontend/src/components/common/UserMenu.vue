<template>
  <div class="user-menu">
    <el-dropdown trigger="click">
      <span class="user-trigger">
        <el-avatar :size="28" icon="UserFilled" />
        <span class="user-name">{{ auth.user?.name || '未登录' }}</span>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled>
            当前角色: {{ roleLabel }}
          </el-dropdown-item>
          <el-dropdown-item divided>
            <div class="role-switcher">
              <div class="switch-label">切换角色（开发用）</div>
              <div class="switch-options">
                <el-tag v-for="r in roles" :key="r.value" :type="r.value === auth.user?.role ? 'primary' : 'info'"
                        size="small" class="role-tag" @click="auth.switchRole(r.value)">
                  {{ r.label }}
                </el-tag>
              </div>
            </div>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/useAuthStore'
import type { UserRole } from '@/types'

const auth = useAuthStore()

const roleLabels: Record<UserRole, string> = {
  admin: '管理员', school_manager: '校领导', grade_leader: '年级组长',
  teacher: '班主任', counselor: '心理老师', student: '学生', parent: '家长',
}
const roleLabel = computed(() => auth.user ? roleLabels[auth.user.role] || '' : '')

const roles: { value: UserRole; label: string }[] = [
  { value: 'admin', label: '管理员' },
  { value: 'school_manager', label: '校领导' },
  { value: 'grade_leader', label: '年级组长' },
  { value: 'teacher', label: '班主任' },
  { value: 'counselor', label: '心理老师' },
  { value: 'student', label: '学生' },
  { value: 'parent', label: '家长' },
]
</script>

<style scoped>
.user-menu { cursor: pointer; }
.user-trigger { display: flex; align-items: center; gap: var(--space-2); color: var(--color-on-primary); }
.user-name { font-size: var(--text-sm); }

.role-switcher { min-width: 200px; }
.switch-label { font-size: 11px; color: #909399; margin-bottom: 6px; }
.switch-options { display: flex; flex-wrap: wrap; gap: 4px; }
.role-tag { cursor: pointer; }
</style>
