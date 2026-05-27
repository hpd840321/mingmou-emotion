<template>
  <div class="school-overview">
    <div class="page-header"><h2>校级大盘</h2></div>

    <KpiCardRow v-if="store.overviewData" :kpis="store.overviewData.kpis" />

    <div class="chart-grid">
      <div class="chart-card">
        <h3>年级情绪健康度对比</h3>
        <div ref="gradeChartRef" class="chart-box"></div>
      </div>
      <div class="chart-card">
        <h3>异常情绪率排行 Top 5</h3>
        <div v-if="store.overviewData" class="ranking-list">
          <div v-for="(item, i) in store.overviewData.alertRanking" :key="i" class="ranking-item">
            <span class="rank-num">{{ i + 1 }}</span>
            <span>{{ item.className }}</span>
            <span class="rank-rate">{{ (item.rate * 100).toFixed(1) }}%</span>
          </div>
        </div>
      </div>
    </div>

    <div class="chart-card full">
      <h3>全校情绪健康度趋势</h3>
      <div ref="trendChartRef" class="chart-box tall"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from 'vue'
import { useSchoolStore } from '@/stores/useSchoolStore'
import KpiCardRow from '@/components/common/KpiCardRow.vue'
import * as echarts from 'echarts'

const store = useSchoolStore()
const gradeChartRef = ref<HTMLDivElement>()
const trendChartRef = ref<HTMLDivElement>()
let gradeChart: echarts.ECharts | null = null
let trendChart: echarts.ECharts | null = null

onMounted(() => { store.loadOverview() })

watch(() => store.overviewData, async () => {
  await nextTick()
  if (!store.overviewData) return

  if (gradeChartRef.value) {
    gradeChart = echarts.init(gradeChartRef.value)
    gradeChart.setOption({
      tooltip: { trigger: 'item' },
      xAxis: { type: 'value' },
      yAxis: { type: 'category', data: store.overviewData.gradeComparison.map(g => g.name) },
      series: [{ type: 'bar', data: store.overviewData.gradeComparison.map(g => g.value), itemStyle: { color: '#3B82F6' } }],
    })
  }

  if (trendChartRef.value) {
    trendChart = echarts.init(trendChartRef.value)
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: ['第1周', '第2周', '第3周', '第4周'] },
      yAxis: { type: 'value', min: 0, max: 100 },
      series: [{ type: 'line', data: [72, 75, 68, 78], smooth: true, areaStyle: { opacity: 0.3 }, itemStyle: { color: '#1E40AF' } }],
    })
  }
})
</script>

<style scoped>
.page-header { margin-bottom: var(--space-6); }
.page-header h2 { font-size: var(--text-xl); font-weight: 600; }
.chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-4); margin-bottom: var(--space-4); }
.chart-card { background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); box-shadow: var(--shadow-card); }
.chart-card.full { grid-column: 1 / -1; }
.chart-card h3 { font-size: var(--text-base); font-weight: 600; margin-bottom: var(--space-3); }
.chart-box { height: 300px; }
.chart-box.tall { height: 350px; }
.ranking-list { display: flex; flex-direction: column; gap: var(--space-2); }
.ranking-item { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-2) 0; border-bottom: 1px solid var(--color-border); font-size: var(--text-sm); }
.rank-num { width: 24px; height: 24px; border-radius: 50%; background: var(--color-muted); display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: var(--text-xs); }
.rank-rate { margin-left: auto; font-weight: 500; color: var(--color-destructive); }
</style>
