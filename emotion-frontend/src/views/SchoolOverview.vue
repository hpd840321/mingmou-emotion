<template>
  <div class="school-overview">
    <div class="page-header"><h2>校级大盘</h2></div>

    <div v-if="store.loading" class="loading-state"><el-skeleton :rows="6" animated /></div>

    <template v-else-if="store.overviewData">
      <KpiCardRow :kpis="store.overviewData.kpis" />

      <div class="chart-grid">
        <div class="chart-card">
          <h3>年级情绪健康度对比</h3>
          <div v-if="hasGradeData" ref="gradeChartRef" class="chart-box"></div>
          <el-empty v-else description="暂无年级数据" />
        </div>
        <div class="chart-card">
          <h3>异常情绪率排行 Top 5</h3>
          <div v-if="store.overviewData.alertRanking.length" class="ranking-list">
            <div v-for="(item, i) in store.overviewData.alertRanking" :key="i" class="ranking-item">
              <span class="rank-num">{{ i + 1 }}</span>
              <span>{{ item.className }}</span>
              <span class="rank-rate">{{ (item.rate * 100).toFixed(1) }}%</span>
            </div>
          </div>
          <el-empty v-else description="暂无异常数据" />
        </div>
      </div>

      <div class="chart-card full">
        <h3>全校情绪健康度趋势</h3>
        <div ref="trendChartRef" class="chart-box tall"></div>
      </div>
    </template>

    <el-empty v-else description="暂无数据" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useSchoolStore } from '@/stores/useSchoolStore'
import KpiCardRow from '@/components/common/KpiCardRow.vue'
import * as echarts from 'echarts'

const store = useSchoolStore()
const gradeChartRef = ref<HTMLDivElement>()
const trendChartRef = ref<HTMLDivElement>()
const hasGradeData = computed(() => store.overviewData?.gradeComparison?.length)

onMounted(() => store.loadOverview())

watch(() => store.overviewData, async () => {
  await nextTick()
  if (!store.overviewData) return
  if (gradeChartRef.value && hasGradeData.value) {
    const chart = echarts.init(gradeChartRef.value)
    chart.setOption({
      tooltip: { trigger: 'item' },
      grid: { left: 100, right: 30 },
      xAxis: { type: 'value', max: 100 },
      yAxis: { type: 'category', data: store.overviewData.gradeComparison.map(g => g.name) },
      series: [{ type: 'bar', data: store.overviewData.gradeComparison.map(g => g.value), itemStyle: { color: '#3B82F6', borderRadius: [0, 4, 4, 0] } }],
    })
  }
  if (trendChartRef.value) {
    const chart = echarts.init(trendChartRef.value)
    chart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 50, right: 30 },
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
.loading-state { padding: var(--space-8); }
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
