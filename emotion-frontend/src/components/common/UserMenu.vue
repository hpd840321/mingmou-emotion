<template>
  <div class="user-menu" v-if="auth.user">
    <el-dropdown trigger="click">
      <span class="user-trigger">
        <el-avatar :size="32" icon="UserFilled" />
        <span class="user-name">{{ auth.user.name }}</span>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled>角色: {{ roleLabel }}</el-dropdown-item>
          <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/useAuthStore'
const auth = useAuthStore()
const roleLabels: Record<string, string> = { admin:'管理员', school_manager:'校级管理者', grade_leader:'年级组长', teacher:'班主任', counselor:'心理老师', student:'学生', parent:'家长' }
const roleLabel = computed(() => auth.user ? roleLabels[auth.user.role] || '' : '')
function handleLogout() { window.location.href = '/login' }
</script>

<style scoped>
.user-menu { cursor: pointer; }
.user-trigger { display: flex; align-items: center; gap: var(--space-2); color: var(--color-on-primary); }
.user-name { font-size: var(--text-sm); }
</style>
