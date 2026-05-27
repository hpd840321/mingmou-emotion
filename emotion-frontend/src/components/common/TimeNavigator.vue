<template>
  <div class="time-nav">
    <el-button :icon="ArrowLeft" circle size="small" @click="$emit('prev')" />
    <span class="time-date">{{ date }}</span>
    <span class="time-sep">│</span>
    <div class="time-periods">
      <span v-for="p in periods" :key="p.value" class="period-tab"
            :class="{ active: modelValue === p.value }"
            @click="$emit('update:modelValue', p.value)">{{ p.label }}</span>
    </div>
    <el-button :icon="ArrowRight" circle size="small" @click="$emit('next')" />
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'

defineProps<{ modelValue: string; date: string }>()
defineEmits(['update:modelValue', 'prev', 'next'])

const periods = [
  { label: '早读', value: 'arrival' }, { label: '第1节', value: 'period_1' },
  { label: '第2节', value: 'period_2' }, { label: '第3节', value: 'period_3' },
  { label: '课间', value: 'recess' }, { label: '第4节', value: 'period_4' },
  { label: '第5节', value: 'period_5' }, { label: '午休', value: 'lunch' },
  { label: '第6节', value: 'period_6' }, { label: '第7节', value: 'period_7' },
  { label: '第8节', value: 'period_8' }, { label: '课外', value: 'afterclass' },
]
</script>

<style scoped>
.time-nav { display: flex; align-items: center; gap: var(--space-3); margin-bottom: var(--space-4); flex-wrap: wrap; }
.time-date { font-weight: 600; font-size: var(--text-base); white-space: nowrap; }
.time-sep { color: var(--color-border); }
.time-periods { display: flex; gap: 2px; overflow-x: auto; flex: 1; }
.period-tab {
  padding: 4px 12px; font-size: var(--text-xs); cursor: pointer;
  border-radius: var(--radius-sm); color: var(--color-muted-fg);
  white-space: nowrap; transition: all 0.15s;
}
.period-tab:hover { background: var(--color-muted); color: var(--color-fg); }
.period-tab.active { background: var(--color-primary); color: white; font-weight: 500; }
</style>
