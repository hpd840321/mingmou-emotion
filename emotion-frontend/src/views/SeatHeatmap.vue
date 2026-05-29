<template>
  <div class="seat-heatmap">
    <div class="page-header">
      <h2>座位热力图</h2>
      <div class="time-selector">
        <el-date-picker v-model="currentDate" type="date" size="small" @change="loadData" />
        <el-select v-model="currentPeriod" size="small" @change="loadData">
          <el-option v-for="p in periods" :key="p.value" :label="p.label" :value="p.value" />
        </el-select>
      </div>
    </div>

    <div class="seat-matrix" v-if="store.heatmapData">
      <div class="podium">讲 台</div>
      <div v-for="row in store.heatmapData.rows" :key="row" class="seat-row">
        <div v-for="col in store.heatmapData.cols" :key="col" class="seat-cell"
             :style="{ background: cellColor(getSeat(row - 1, col - 1)) }">
          <span class="seat-name">{{ getSeat(row - 1, col - 1)?.studentName || '' }}</span>
          <span class="seat-engagement">{{ getSeat(row - 1, col - 1)?.engagement ?? '' }}</span>
        </div>
      </div>
    </div>

    <div class="chart-card">
      <h3>分布统计</h3>
      <div ref="distChartRef" class="chart-box"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from 'vue'
import { useClassStore } from '@/stores/useClassStore'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'

const route = useRoute()
const store = useClassStore()
const classId = Number(route.params.classId)
const currentDate = ref(new Date().toISOString().slice(0, 10))
const currentPeriod = ref('period_1')
const distChartRef = ref<HTMLDivElement>()

const periods = [
  { label: '早读', value: 'arrival' }, { label: '第1节', value: 'period_1' },
  { label: '第2节', value: 'period_2' }, { label: '第3节', value: 'period_3' },
  { label: '课间操', value: 'recess' }, { label: '第4节', value: 'period_4' },
  { label: '第5节', value: 'period_5' }, { label: '午休', value: 'lunch' },
  { label: '第6节', value: 'period_6' }, { label: '第7节', value: 'period_7' },
  { label: '第8节', value: 'period_8' }, { label: '课外', value: 'afterclass' },
]

onMounted(() => loadData())

function loadData() {
  store.loadHeatmap(classId, { date: currentDate.value, period_label: currentPeriod.value })
}

function getSeat(row: number, col: number) {
  return store.heatmapData?.seats.find(s => s.row === row && s.col === col)
}

function cellColor(seat: any): string {
  if (!seat || seat.isEmpty) return '#f1f5f9'
  if (seat.isAbsent) return '#fecaca'
  const e = seat.engagement ?? 0
  if (e >= 70) return '#bbf7d0'
  if (e >= 40) return '#fde68a'
  return '#fed7aa'
}

watch(() => store.heatmapData, async () => {
  await nextTick()
  if (!distChartRef.value || !store.heatmapData) return
  const chart = echarts.init(distChartRef.value)
  chart.setOption({
    tooltip: { trigger: 'item' },
    xAxis: { type: 'category', data: (store.heatmapData.distribution || []).map((d: any) => d.label) },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', data: (store.heatmapData.distribution || []).map((d: any) => d.count), itemStyle: { color: '#3B82F6' } }],
  })
})
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-4); }
.page-header h2 { font-size: var(--text-xl); font-weight: 600; }
.time-selector { display: flex; gap: var(--space-2); }
.podium { text-align: center; padding: var(--space-2); background: var(--color-muted); border-radius: var(--radius-md); margin-bottom: var(--space-3); font-size: var(--text-sm); color: var(--color-muted-fg); }
.seat-matrix { background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); box-shadow: var(--shadow-card); margin-bottom: var(--space-4); }
.seat-row { display: flex; gap: var(--space-2); margin-bottom: var(--space-2); justify-content: center; }
.seat-cell { width: 100px; height: 60px; border-radius: var(--radius-sm); display: flex; flex-direction: column; align-items: center; justify-content: center; font-size: var(--text-xs); cursor: pointer; transition: transform 0.15s; }
.seat-cell:hover { transform: scale(1.1); }
.seat-name { font-weight: 500; }
.seat-engagement { color: var(--color-muted-fg); }
.chart-card { background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); }
.chart-card h3 { font-size: var(--text-base); font-weight: 600; margin-bottom: var(--space-3); }
.chart-box { height: 250px; }
</style>
