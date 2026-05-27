<template>
  <div class="kpi-card">
    <div class="kpi-label">{{ label }}</div>
    <div class="kpi-value">
      <span class="kpi-number">{{ value }}</span>
      <span class="kpi-unit">{{ unit }}</span>
      <span v-if="change !== null && change !== undefined" class="kpi-change" :class="changeDirection">
        {{ changeDirection === 'up' ? '↑' : changeDirection === 'down' ? '↓' : '→' }} {{ Math.abs(change as number) }}%
      </span>
    </div>
    <div class="kpi-status" :class="`status-${status}`">{{ statusLabel }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{
  label: string; value: number; unit?: string; change?: number | null;
  changeDirection?: 'up' | 'down' | 'flat'; status?: 'good' | 'warning' | 'danger' | 'neutral'
}>()
const statusLabel = computed(() => ({ good: '正常范围', warning: '需关注', danger: '异常', neutral: '—' })[props.status || 'neutral'])
</script>

<style scoped>
.kpi-card { background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); box-shadow: var(--shadow-card); }
.kpi-label { font-size: var(--text-xs); color: var(--color-muted-fg); margin-bottom: var(--space-2); }
.kpi-value { display: flex; align-items: baseline; gap: var(--space-1); margin-bottom: var(--space-2); }
.kpi-number { font-family: var(--font-mono); font-size: var(--text-2xl); font-weight: 700; color: var(--color-fg); }
.kpi-unit { font-size: var(--text-sm); color: var(--color-muted-fg); }
.kpi-change { font-size: var(--text-xs); font-weight: 500; margin-left: auto; }
.kpi-change.up { color: var(--emotion-happy); }
.kpi-change.down { color: var(--color-destructive); }
.kpi-change.flat { color: var(--color-muted-fg); }
.kpi-status { font-size: var(--text-xs); }
.status-good { color: var(--emotion-happy); }
.status-warning { color: var(--color-accent); }
.status-danger { color: var(--color-destructive); }
.status-neutral { color: var(--color-muted-fg); }
</style>
