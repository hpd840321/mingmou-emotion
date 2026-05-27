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
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-4); margin-bottom: var(--space-6); }
@media (max-width: 1023px) { .kpi-row { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 767px) { .kpi-row { grid-template-columns: 1fr; } }
</style>
