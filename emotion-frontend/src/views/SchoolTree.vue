<template>
  <div class="school-tree">
    <div class="tree-panel">
      <div class="tree-header"><h3>学校组织</h3></div>
      <el-tree
        :data="treeData"
        :props="treeProps"
        node-key="id"
        highlight-current
        @node-click="onNodeClick"
        :expand-on-click-node="true"
      >
        <template #default="{ node, data }">
          <span class="tree-node">
            <span class="node-icon">{{ iconMap[data.type] }}</span>
            <span>{{ data.label }}</span>
            <span v-if="data.type === 'student'" class="node-no">{{ data.studentNo }}</span>
          </span>
        </template>
      </el-tree>
    </div>

    <div class="detail-panel">
      <!-- 未选择 -->
      <div v-if="!selectedNode" class="empty-state">
        <h2>👈 从左侧选择一个节点</h2>
        <p>选择年级查看班级对比，选择班级跳转看板，选择学生查看详情和原始数据</p>
      </div>

      <!-- 年级选中 -->
      <div v-else-if="selectedNode.type === 'grade'" class="grade-detail">
        <h2>{{ selectedNode.label }}</h2>
        <div class="quick-links">
          <el-button v-for="child in selectedNode.children" :key="child.id"
            @click="$router.push(`/class/${child.classId}/dashboard`)" size="small">
            📋 {{ child.label }}
          </el-button>
        </div>
      </div>

      <!-- 班级选中 -->
      <div v-else-if="selectedNode.type === 'class'" class="class-detail">
        <h2>{{ selectedNode.label }}</h2>
        <div class="quick-links">
          <el-button @click="$router.push(`/class/${selectedNode.classId}/dashboard`)" size="small">📋 班级看板</el-button>
          <el-button @click="$router.push(`/class/${selectedNode.classId}/heatmap`)" size="small">🔥 座位热力图</el-button>
        </div>
        <div class="student-list">
          <el-card v-for="stu in selectedNode.children" :key="stu.id" class="stu-card" shadow="hover"
            @click="selectStudent(stu)" :class="{ active: currentStudent?.id === stu.id }">
            <div class="stu-name">{{ stu.label }}</div>
            <div class="stu-no">{{ stu.studentNo }}</div>
          </el-card>
        </div>
      </div>

      <!-- 学生选中：详情 + 原始数据 -->
      <div v-else-if="selectedNode.type === 'student'" class="student-detail">
        <div class="stu-header">
          <div>
            <h2>{{ selectedNode.label }}</h2>
            <span class="stu-meta">学号: {{ selectedNode.studentNo }}</span>
          </div>
          <div class="stu-actions">
            <el-button type="primary" size="small" @click="$router.push(`/student/${selectedNode.studentId}/profile`)">
              查看完整档案 →
            </el-button>
          </div>
        </div>

        <el-tabs v-model="activeTab">
          <el-tab-pane label="情绪分析" name="analysis">
            <div v-if="loadingEmotions" class="loading"><el-skeleton :rows="5" animated /></div>
            <div v-else-if="rawEmotions.length === 0" class="empty">暂无情绪数据</div>
            <template v-else>
              <div class="chart-summary">
                <div class="stat-card" v-for="s in emotionStats" :key="s.label">
                  <div class="stat-value" :style="{ color: s.color }">{{ (s.pct * 100).toFixed(0) }}%</div>
                  <div class="stat-label">{{ s.label }}</div>
                </div>
              </div>
              <div ref="emotionChartRef" class="chart-box"></div>
            </template>
          </el-tab-pane>

          <el-tab-pane label="原始数据" name="raw">
            <el-table :data="rawEmotions" size="small" stripe max-height="500">
              <el-table-column label="时间" width="160">
                <template #default="{ row }">{{ formatTime(row.captureTime) }}</template>
              </el-table-column>
              <el-table-column label="课时" width="80" prop="periodLabel" />
              <el-table-column label="主导表情" width="100">
                <template #default="{ row }">{{ emotionIcon(row.dominantEmotion) }} {{ row.dominantEmotion }}</template>
              </el-table-column>
              <el-table-column label="置信度" width="80" prop="dominantConfidence">
                <template #default="{ row }">{{ (row.dominantConfidence * 100).toFixed(0) }}%</template>
              </el-table-column>
              <el-table-column label="表情分布" min-width="200">
                <template #default="{ row }">
                  <div class="emotion-bar">
                    <div v-for="(v, k) in row.emotions" :key="k" class="emotion-seg"
                      :style="{ width: ((v || 0) * 100) + '%', background: emotionColors[k] }"
                      :title="k + ': ' + ((v || 0) * 100).toFixed(0) + '%'" />
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { fetchSchoolTree, fetchStudentRawEmotions, type TreeNode, type RawEmotionRecord } from '@/api/schoolTree'
import * as echarts from 'echarts'

const router = useRouter()
const treeData = ref<TreeNode[]>([])
const treeProps = { children: 'children', label: 'label' }
const iconMap: Record<string, string> = { grade: '📚', class: '📋', student: '👤' }
const emotionColors: Record<string, string> = {
  happy: '#22C55E', sad: '#F97316', angry: '#DC2626', surprise: '#F59E0B',
  fear: '#7C3AED', disgust: '#374151', neutral: '#64748B'
}

const selectedNode = ref<TreeNode | null>(null)
const currentStudent = ref<TreeNode | null>(null)
const activeTab = ref('analysis')
const rawEmotions = ref<RawEmotionRecord[]>([])
const loadingEmotions = ref(false)
const emotionChartRef = ref<HTMLDivElement>()

onMounted(() => { fetchSchoolTree().then(d => treeData.value = d) })

function onNodeClick(data: TreeNode) {
  selectedNode.value = data
  if (data.type === 'student') {
    loadStudentEmotions(data)
    currentStudent.value = data
  } else {
    currentStudent.value = null
    rawEmotions.value = []
  }
}

function selectStudent(stu: TreeNode) {
  selectedNode.value = stu
  currentStudent.value = stu
  loadStudentEmotions(stu)
}

async function loadStudentEmotions(stu: TreeNode) {
  if (!stu.studentId) return
  loadingEmotions.value = true
  try {
    rawEmotions.value = await fetchStudentRawEmotions(stu.studentId)
  } catch { rawEmotions.value = [] }
  finally { loadingEmotions.value = false }
}

const emotionStats = computed(() => {
  const counts: Record<string, number> = {}
  rawEmotions.value.forEach(r => {
    counts[r.dominantEmotion] = (counts[r.dominantEmotion] || 0) + 1
  })
  const total = rawEmotions.value.length || 1
  return Object.entries(counts).map(([label, count]) => ({
    label, pct: count / total, color: emotionColors[label] || '#999'
  }))
})

watch(rawEmotions, async () => {
  await nextTick()
  if (!emotionChartRef.value || rawEmotions.value.length === 0) return
  const chart = echarts.init(emotionChartRef.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['快乐', '悲伤', '愤怒', '中性', '惊讶', '恐惧'] },
    grid: { left: 50, right: 20 },
    xAxis: { type: 'category', data: rawEmotions.value.map(r => formatTime(r.captureTime)) },
    yAxis: { type: 'value', max: 100 },
    series: [
      { name: '快乐', type: 'line', data: rawEmotions.value.map(r => (r.emotions.happy || 0) * 100), smooth: true, lineStyle: { color: '#22C55E' } },
      { name: '悲伤', type: 'line', data: rawEmotions.value.map(r => (r.emotions.sad || 0) * 100), smooth: true, lineStyle: { color: '#F97316' } },
    ],
  })
})

function formatTime(ts: string) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}
function emotionIcon(e: string) { return { happy:'😊', sad:'😢', angry:'😠', surprise:'😲', fear:'😨', disgust:'😖', neutral:'😐' }[e] || '' }
</script>

<style scoped>
.school-tree { display: flex; height: calc(100vh - var(--topbar-height) - var(--breadcrumb-height) - 48px); gap: var(--space-4); }
.tree-panel { width: 280px; flex-shrink: 0; background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); overflow-y: auto; padding: var(--space-3); }
.tree-header { margin-bottom: var(--space-3); }
.tree-header h3 { font-size: var(--text-base); font-weight: 600; }
.tree-node { display: flex; align-items: center; gap: 6px; font-size: var(--text-sm); }
.node-icon { font-size: 16px; }
.node-no { font-size: var(--text-xs); color: var(--color-muted-fg); margin-left: auto; }
.detail-panel { flex: 1; background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-6); overflow-y: auto; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: var(--color-muted-fg); }
.empty-state h2 { font-size: var(--text-lg); margin-bottom: var(--space-2); }
.quick-links { display: flex; gap: var(--space-2); flex-wrap: wrap; margin: var(--space-4) 0; }
.student-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: var(--space-2); }
.stu-card { cursor: pointer; text-align: center; transition: all 0.15s; }
.stu-card:hover { border-color: var(--color-primary); }
.stu-card.active { border-color: var(--color-primary); background: #EFF6FF; }
.stu-name { font-weight: 500; }
.stu-no { font-size: var(--text-xs); color: var(--color-muted-fg); }
.stu-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: var(--space-4); }
.stu-header h2 { font-size: var(--text-xl); font-weight: 600; }
.stu-meta { font-size: var(--text-sm); color: var(--color-muted-fg); }
.chart-summary { display: flex; gap: var(--space-4); margin-bottom: var(--space-4); }
.stat-card { text-align: center; padding: var(--space-3); background: var(--color-bg); border-radius: var(--radius-md); min-width: 80px; }
.stat-value { font-size: var(--text-xl); font-weight: 700; }
.stat-label { font-size: var(--text-xs); color: var(--color-muted-fg); }
.chart-box { height: 280px; }
.emotion-bar { display: flex; height: 12px; border-radius: 6px; overflow: hidden; }
.emotion-seg { height: 100%; transition: width 0.2s; }
.loading { padding: var(--space-8); }
.empty { text-align: center; padding: var(--space-8); color: var(--color-muted-fg); }
</style>
