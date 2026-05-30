<template>
  <div class="student-profile">
    <div v-if="store.loading" class="loading-state"><el-skeleton :rows="8" animated /></div>

    <template v-else-if="store.profileData">
      <div class="student-header">
        <div class="student-info">
          <h2>{{ store.profileData.studentName }}</h2>
          <span class="student-meta">学号: {{ store.profileData.studentNo }} · {{ store.profileData.className }}</span>
        </div>
        <div class="student-tags">
          <el-tag v-for="tag in store.profileData.tags" :key="tag" :type="tagType(tag)" size="small">{{ tag }}</el-tag>
        </div>
      </div>

      <KpiCardRow :kpis="store.profileData.kpis || []" />

      <div class="chart-grid">
        <div class="chart-card">
          <h3>情绪变化趋势</h3>
          <div ref="trendRef" class="chart-box"></div>
        </div>
        <div class="chart-card">
          <h3>表情分布（本周）</h3>
          <div ref="pieRef" class="chart-box"></div>
        </div>
      </div>

      <div class="chart-card" v-if="store.profileData.alertTimeline?.length">
        <h3>异常事件时间线</h3>
        <el-timeline>
          <el-timeline-item v-for="evt in store.profileData.alertTimeline" :key="evt.date + evt.period"
            :timestamp="evt.date" placement="top">
            <p>{{ evt.period }}: {{ evt.desc }} (触发值: {{ evt.triggerValue }})</p>
          </el-timeline-item>
        </el-timeline>
      </div>
    </template>

    <el-empty v-else description="暂无学生数据" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from 'vue'
import { useStudentStore } from '@/stores/useStudentStore'
import { useRoute } from 'vue-router'
import KpiCardRow from '@/components/common/KpiCardRow.vue'
import * as echarts from 'echarts'

const route = useRoute()
const store = useStudentStore()
const studentId = Number(route.params.studentId)
const trendRef = ref<HTMLDivElement>()
const pieRef = ref<HTMLDivElement>()

onMounted(() => store.loadProfile(studentId))

watch(() => store.profileData, async () => {
  await nextTick()
  if (!store.profileData) return
    if (trendRef.value) {
      const chart = echarts.init(trendRef.value)
      const trendData = store.profileData.trendData || []
      chart.setOption({
        tooltip: { trigger: 'axis' },
        grid: { left: 50, right: 30 },
        xAxis: { type: 'category', data: trendData.map((t: any) => t.date) },
        yAxis: { type: 'value', min: 0, max: 100 },
        series: [
          { name: '快乐', type: 'line', data: trendData.map((t: any) => (t.happy || 0) * 100), smooth: true, itemStyle: { color: '#22C55E' } },
          { name: '悲伤', type: 'line', data: trendData.map((t: any) => (t.sad || 0) * 100), smooth: true, itemStyle: { color: '#F97316' } },
        ],
      })
    }
  if (pieRef.value) {
    const chart = echarts.init(pieRef.value)
    const wd = store.profileData.weekDistribution || { happy: 0, sad: 0, neutral: 0, angry: 0, surprise: 0, fear: 0, disgust: 0 }
    chart.setOption({
      tooltip: { trigger: 'item' },
      series: [{ type: 'pie', radius: ['40%', '70%'], data: [
        { name: '快乐', value: wd.happy || 0, itemStyle: { color: '#22C55E' } },
        { name: '悲伤', value: wd.sad || 0, itemStyle: { color: '#F97316' } },
        { name: '中性', value: wd.neutral || 0, itemStyle: { color: '#64748B' } },
        { name: '愤怒', value: wd.angry || 0, itemStyle: { color: '#DC2626' } },
      ]}],
    })
  }
})

function tagType(tag: string): string {
  if (tag.includes('情绪')) return 'warning'
  if (tag.includes('学业')) return 'primary'
  return 'info'
}
</script>

<style scoped>
.loading-state { padding: var(--space-8); }
.student-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-6); }
.student-info h2 { font-size: var(--text-xl); font-weight: 600; }
.student-meta { font-size: var(--text-sm); color: var(--color-muted-fg); }
.student-tags { display: flex; gap: var(--space-2); }
.chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-4); margin-bottom: var(--space-4); }
.chart-card { background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); box-shadow: var(--shadow-card); margin-bottom: var(--space-4); }
.chart-card h3 { font-size: var(--text-base); font-weight: 600; margin-bottom: var(--space-3); }
.chart-box { height: 300px; }
</style>
