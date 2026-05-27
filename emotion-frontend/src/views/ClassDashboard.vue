<template>
  <div class="class-dashboard">
    <div class="page-header"><h2>班级情绪看板</h2></div>

    <TimeNavigator v-model="currentPeriod" :date="currentDate" @prev="goPrev" @next="goNext" />

    <KpiCardRow v-if="store.dashboardData" :kpis="store.dashboardData.kpis" />

    <div class="chart-card">
      <h3>课堂情绪时间线</h3>
      <div ref="timelineRef" class="chart-box"></div>
    </div>

    <div class="chart-card">
      <h3>学生表情详情</h3>
      <el-table :data="store.dashboardData?.students || []" style="width:100%" size="small" stripe>
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="studentNo" label="学号" width="100" />
        <el-table-column label="主导表情" width="120">
          <template #default="{ row }">
            <span>{{ emotionIcon(row.dominantEmotion) }} {{ row.dominantEmotion }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="happy" label="快乐%" width="80" />
        <el-table-column prop="neutral" label="中性%" width="80" />
        <el-table-column prop="engagement" label="参与度" width="120">
          <template #default="{ row }">
            <el-progress :percentage="row.engagement" :status="row.engagement > 60 ? 'success' : row.engagement > 30 ? 'warning' : 'exception'" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" text @click="$router.push(`/student/${row.id}/profile`)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from 'vue'
import { useClassStore } from '@/stores/useClassStore'
import { useRoute } from 'vue-router'
import TimeNavigator from '@/components/common/TimeNavigator.vue'
import KpiCardRow from '@/components/common/KpiCardRow.vue'
import * as echarts from 'echarts'

const route = useRoute()
const store = useClassStore()
const classId = Number(route.params.classId)
const currentPeriod = ref('period_1')
const currentDate = ref(new Date().toISOString().slice(0, 10))
const timelineRef = ref<HTMLDivElement>()

onMounted(() => { loadData() })

function loadData() {
  store.loadDashboard(classId, { date: currentDate.value, period_label: currentPeriod.value })
}

watch(currentPeriod, () => loadData())

watch(() => store.dashboardData, async () => {
  await nextTick()
  if (!timelineRef.value || !store.dashboardData) return
  const chart = echarts.init(timelineRef.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['快乐', '悲伤', '愤怒', '中性'] },
      xAxis: { type: 'category', data: store.dashboardData.timelineData.map((t: any) => t.time) },
    yAxis: { type: 'value', max: 100 },
      series: [
        { name: '快乐', type: 'line', data: store.dashboardData.timelineData.map((t: any) => t.happy * 100), smooth: true, areaStyle: { opacity: 0.3 } },
        { name: '悲伤', type: 'line', data: store.dashboardData.timelineData.map((t: any) => t.sad * 100), smooth: true, areaStyle: { opacity: 0.3 } },
      ],
  })
})

function goPrev() { loadData() }
function goNext() { loadData() }
function emotionIcon(e: string) { return { happy:'😊', sad:'😢', angry:'😠', surprise:'😲', fear:'😨', disgust:'😖', neutral:'😐' }[e] || '' }
</script>

<style scoped>
.page-header { margin-bottom: var(--space-4); }
.page-header h2 { font-size: var(--text-xl); font-weight: 600; }
.chart-card { background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); box-shadow: var(--shadow-card); margin-bottom: var(--space-4); }
.chart-card h3 { font-size: var(--text-base); font-weight: 600; margin-bottom: var(--space-3); }
.chart-box { height: 300px; }
</style>
