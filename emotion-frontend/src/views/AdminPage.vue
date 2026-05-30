<template>
  <div class="admin-page">
    <h2>系统管理</h2>

    <el-tabs v-model="activeTab" class="admin-tabs">
      <el-tab-pane label="年级管理" name="grade">
        <el-table :data="grades" stripe>
          <el-table-column prop="name" label="年级" />
          <el-table-column prop="sortOrder" label="排序" width="80" />
          <el-table-column label="操作" width="150">
            <template #default>
              <el-button size="small" text>编辑</el-button>
              <el-button size="small" text type="danger">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin-top:12px">+ 新增年级</el-button>
      </el-tab-pane>

      <el-tab-pane label="班级管理" name="class">
        <el-table :data="classes" stripe>
          <el-table-column prop="name" label="班级" />
          <el-table-column label="年级" width="120">
            <template #default="{ row }">初一</template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="80" />
          <el-table-column label="VisionMind 库" width="200">
            <template #default="{ row }">
              <el-tag v-if="row.vmLibId" type="success" size="small">已创建</el-tag>
              <el-tag v-else type="warning" size="small">未创建</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default>
              <el-button size="small" text>编辑</el-button>
              <el-button size="small" text type="primary">创建人脸库</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="系统配置" name="config">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="VisionMind API地址">http://localhost:8080</el-descriptions-item>
          <el-descriptions-item label="图片存储路径">./images</el-descriptions-item>
          <el-descriptions-item label="数据保留天数">90</el-descriptions-item>
          <el-descriptions-item label="默认预警阈值">0.6</el-descriptions-item>
        </el-descriptions>
        <el-button size="small" type="primary" style="margin-top:12px">编辑配置</el-button>
      </el-tab-pane>

      <el-tab-pane label="数据导入" name="import">
        <el-alert title="将 data/ 目录下的历史图片导入系统并触发识别管线" type="info" show-icon style="margin-bottom:16px" />
        <el-button type="primary" @click="startImport" :loading="importing" :disabled="importing">
          {{ importing ? '导入中...' : '开始导入历史数据' }}
        </el-button>
        <div v-if="importResult" style="margin-top:12px">
          <p>总计: {{ importResult.total }} | 成功: {{ importResult.imported }} | 失败: {{ importResult.failed }}</p>
        </div>
      </el-tab-pane>

      <el-tab-pane label="管道监控" name="pipeline">
        <div class="pipeline-dashboard">
          <!-- Status summary cards -->
          <div class="status-cards">
            <div class="status-card pending">
              <div class="card-number">{{ status.pending }}</div>
              <div class="card-label">待处理</div>
            </div>
            <div class="status-card processing">
              <div class="card-number">{{ status.processing }}</div>
              <div class="card-label">处理中</div>
            </div>
            <div class="status-card completed">
              <div class="card-number">{{ status.completed }}</div>
              <div class="card-label">已完成</div>
            </div>
            <div class="status-card failed">
              <div class="card-number">{{ status.failed }}</div>
              <div class="card-label">失败</div>
            </div>
          </div>

          <!-- Progress bar (total processed / total images) -->
          <div class="progress-section">
            <div class="progress-header">
              <span>处理进度</span>
              <span class="progress-text">{{ status.completed + status.failed }} / {{ status.total }}</span>
            </div>
            <el-progress :percentage="progressPercent" :status="progressStatus" :stroke-width="20" />
          </div>

          <!-- Action buttons -->
          <div class="action-bar">
            <el-button type="primary" @click="refreshStatus" :loading="refreshing">刷新状态</el-button>
            <el-button type="success" @click="startPipeline" :loading="pipelineRunning" :disabled="pipelineRunning || status.pending === 0">
              {{ pipelineRunning ? '管线运行中...' : '启动处理管线' }}
            </el-button>
          </div>

          <!-- Live event log -->
          <div class="event-log">
            <h3>实时日志</h3>
            <div class="log-list" ref="logContainer">
              <div v-if="events.length === 0" class="log-empty">暂无事件，启动管线后显示</div>
              <div v-for="(evt, i) in events" :key="i" class="log-item" :class="evt.newStatus">
                <span class="log-time">{{ formatTime(evt.timestamp) }}</span>
                <el-tag :type="statusTag(evt.newStatus)" size="small">{{ evt.newStatus }}</el-tag>
                <span class="log-file">{{ evt.fileName }}</span>
                <span v-if="evt.errorMessage" class="log-error">{{ evt.errorMessage }}</span>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import client from '@/api/client'
import { ElMessage } from 'element-plus'

const activeTab = ref(localStorage.getItem('adminTab') || 'grade')
const importing = ref(false)
const importResult = ref<any>(null)
const refreshing = ref(false)
const pipelineRunning = ref(false)
const logContainer = ref<HTMLElement | null>(null)

const grades = ref([{ name: '初一', sortOrder: 1 }, { name: '初二', sortOrder: 2 }, { name: '初三', sortOrder: 3 }])
const classes = ref([
  { name: '初一班', sortOrder: 1, vmLibId: null },
  { name: '初二(1)班', sortOrder: 1, vmLibId: 'lib-001' },
  { name: '初二(2)班', sortOrder: 2, vmLibId: null },
])

// Pipeline status
const status = ref({ pending: 0, processing: 0, completed: 0, failed: 0, total: 0 })
const events = ref<any[]>([])
const MAX_EVENTS = 200

const progressPercent = computed(() => {
  if (status.value.total === 0) return 0
  return Math.round(((status.value.completed + status.value.failed) / status.value.total) * 100)
})

const progressStatus = computed(() => {
  if (status.value.total === 0) return undefined
  if (status.value.failed > 0 && status.value.failed > status.value.completed * 0.5) return 'exception'
  if (progressPercent.value === 100) return 'success'
  return undefined
})

function statusTag(s: string) {
  switch (s) {
    case 'PENDING': return 'info'
    case 'PROCESSING': return 'warning'
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

function formatTime(ts: string) {
  if (!ts) return ''
  const d = new Date(ts)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

async function refreshStatus() {
  refreshing.value = true
  try {
    const res: any = await client.get('/admin/pipeline/status')
    status.value = res as any
  } catch (e: any) {
    console.error('Failed to fetch pipeline status:', e)
  } finally {
    refreshing.value = false
  }
}

async function startPipeline() {
  pipelineRunning.value = true
  events.value = []
  try {
    await client.post('/admin/pipeline/run')
    ElMessage.success('管线已启动')
  } catch (e: any) {
    ElMessage.error('启动管线失败: ' + (e.message || ''))
  } finally {
    pipelineRunning.value = false
  }
}

async function startImport() {
  importing.value = true
  try {
    const res: any = await client.post('/admin/import', null, { params: { dateDir: './data' } })
    importResult.value = (res as any)
    ElMessage.success('导入完成')
  } catch {
    ElMessage.error('导入失败')
    importResult.value = { total: 0, imported: 0, failed: 0 }
  } finally { importing.value = false }
}

// WebSocket connection for real-time pipeline events
let stompClient: any = null
let pollTimer: number | null = null

function connectWebSocket() {
  // Dynamic import STOMP to avoid breaking if unavailable
  import('@stomp/stompjs').then(({ Client }) => {
    const client = new Client({
                  brokerURL: `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`,
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe('/topic/pipeline-progress', (msg) => {
          try {
            const event = JSON.parse(msg.body)
            events.value.unshift(event)
            if (events.value.length > MAX_EVENTS) events.value.length = MAX_EVENTS
            // Update status counts from event
            if (event.counts) {
              status.value = { ...status.value, ...event.counts, total: Object.values(event.counts).reduce((a: number, b: number) => a + b, 0) }
            }
            nextTick(() => {
              if (logContainer.value) logContainer.value.scrollTop = 0
            })
          } catch (e) { /* ignore parse errors */ }
        })
      },
    })
    client.activate()
    stompClient = client
  }).catch(() => {
    // STOMP not available, fall back to polling
    pollTimer = window.setInterval(refreshStatus, 3000)
  })
}

function disconnectWebSocket() {
  if (stompClient) {
    stompClient.deactivate()
    stompClient = null
  }
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(() => {
  refreshStatus()
  connectWebSocket()
})

onUnmounted(() => {
  disconnectWebSocket()
})
</script>

<style scoped>
.admin-page h2 { font-size: var(--text-xl); font-weight: 600; margin-bottom: var(--space-4); }
.admin-tabs { background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); }

/* Pipeline Dashboard */
.pipeline-dashboard { display: flex; flex-direction: column; gap: 20px; }

.status-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.status-card { padding: 20px; border-radius: 8px; text-align: center; color: #fff; }
.status-card.pending { background: #909399; }
.status-card.processing { background: #e6a23c; }
.status-card.completed { background: #67c23a; }
.status-card.failed { background: #f56c6c; }
.card-number { font-size: 36px; font-weight: 700; }
.card-label { font-size: 14px; margin-top: 4px; opacity: 0.9; }

.progress-section { padding: 16px; background: #f5f7fa; border-radius: 8px; }
.progress-header { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 14px; }
.progress-text { color: #909399; }

.action-bar { display: flex; gap: 12px; }

.event-log { border: 1px solid #dcdfe6; border-radius: 8px; padding: 16px; }
.event-log h3 { margin: 0 0 12px 0; font-size: 16px; }
.log-list { max-height: 360px; overflow-y: auto; }
.log-empty { color: #c0c4cc; text-align: center; padding: 40px 0; font-size: 14px; }
.log-item { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px solid #f2f2f2; font-size: 13px; }
.log-item:last-child { border-bottom: none; }
.log-time { color: #909399; font-family: monospace; font-size: 12px; min-width: 65px; }
.log-file { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.log-error { color: #f56c6c; font-size: 12px; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
