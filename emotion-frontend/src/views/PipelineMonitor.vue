<template>
  <div class="pipeline-page">
    <div class="page-header">
      <h2>处理管线监控</h2>
      <div class="header-meta">
        <span v-if="speed > 0" class="meta-item">处理速度: {{ speed }} 张/秒</span>
        <span v-if="eta" class="meta-item">预计剩余: {{ eta }}</span>
      </div>
    </div>

    <!-- Status summary cards -->
    <div class="status-cards">
      <div class="status-card pending">
        <div class="card-number">{{ dbStatus.pending }}</div>
        <div class="card-label">待处理</div>
      </div>
      <div class="status-card processing" :class="{ pulse: running }">
        <div class="card-number">{{ dbStatus.processing }}</div>
        <div class="card-label">处理中</div>
        <div v-if="running" class="card-indicator">●</div>
      </div>
      <div class="status-card completed">
        <div class="card-number">{{ dbStatus.completed }}</div>
        <div class="card-label">已完成</div>
      </div>
      <div class="status-card failed">
        <div class="card-number">{{ dbStatus.failed }}</div>
        <div class="card-label">失败</div>
      </div>
    </div>

    <!-- Progress bar -->
    <div class="progress-section">
      <div class="progress-header">
        <span>总进度</span>
        <span class="progress-text">{{ dbStatus.completed + dbStatus.failed }} / {{ dbStatus.total }} ({{ progressPercent }}%)</span>
      </div>
      <el-progress :percentage="progressPercent" :status="progressStatus" :stroke-width="24" />
    </div>

    <!-- Action buttons -->
    <div class="action-bar">
      <el-button type="primary" @click="refreshStatus" :loading="refreshing" plain>刷新状态</el-button>
      <el-button v-if="!running" type="success" @click="startPipeline" :disabled="dbStatus.pending === 0">
        启动处理管线
      </el-button>
      <el-button v-else type="danger" @click="stopPipeline">停止处理</el-button>
      <el-button type="warning" @click="resetFailed" :disabled="dbStatus.failed === 0" plain>
        重新处理失败 ({{ dbStatus.failed }} 张)
      </el-button>
    </div>

    <!-- Live event log -->
    <div class="event-log">
      <div class="log-header">
        <h3>实时日志</h3>
        <span class="log-count">{{ events.length }} 条</span>
      </div>
      <div class="log-list" ref="logContainer">
        <div v-if="events.length === 0" class="log-empty">暂无事件</div>
        <div v-for="(evt, i) in events" :key="i" class="log-item" :class="evt.newStatus">
          <span class="log-time">{{ formatTime(evt.timestamp) }}</span>
          <el-tag :type="statusTag(evt.newStatus)" size="small" class="log-status">{{ evt.newStatus }}</el-tag>
          <span class="log-file">{{ evt.fileName }}</span>
          <span v-if="evt.errorMessage" class="log-error" :title="evt.errorMessage">{{ evt.errorMessage }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import client from '@/api/client'
import { ElMessage, ElMessageBox } from 'element-plus'

const refreshing = ref(false)
const running = ref(false)
const speed = ref(0)
const eta = ref('')
const logContainer = ref<HTMLElement | null>(null)
const events = ref<any[]>([])
const MAX_EVENTS = 500

const dbStatus = ref({ pending: 0, processing: 0, completed: 0, failed: 0, total: 0 })

const progressPercent = computed(() => {
  if (dbStatus.value.total === 0) return 0
  return Math.round(((dbStatus.value.completed + dbStatus.value.failed) / dbStatus.value.total) * 100)
})

const progressStatus = computed(() => {
  if (dbStatus.value.total === 0) return undefined
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
    Object.assign(dbStatus.value, res)
    if (res.running !== undefined) running.value = res.running
    if (res.speed !== undefined) speed.value = res.speed
    if (res.eta !== undefined) eta.value = res.eta
  } catch (e: any) {
    console.error('Failed to fetch status:', e)
  } finally {
    refreshing.value = false
  }
}

async function startPipeline() {
  if (dbStatus.value.pending === 0) {
    ElMessage.warning('没有待处理的图片')
    return
  }
  events.value = []
  running.value = true
  try {
    await client.post('/admin/pipeline/run')
    ElMessage.success('管线已启动')
  } catch (e: any) {
    ElMessage.error('启动失败: ' + (e.message || ''))
    running.value = false
  }
}

async function stopPipeline() {
  try {
    await client.post('/admin/pipeline/stop')
    ElMessage.success('停止信号已发送')
  } catch (e: any) {
    ElMessage.error('发送停止信号失败')
  }
}

async function resetFailed() {
  if (dbStatus.value.failed === 0) return
  try {
    await ElMessageBox.confirm(`确定将 ${dbStatus.value.failed} 张失败图片重置为待处理?`, '确认', { type: 'warning' })
    const res: any = await client.post('/admin/pipeline/reset-failed')
    ElMessage.success(res.message || '重置完成')
    await refreshStatus()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('重置失败')
  }
}

// WebSocket
let stompClient: any = null
let pollTimer: number | null = null

function connectWebSocket() {
  import('@stomp/stompjs').then(({ Client }) => {
    const wsUrl = `ws://${window.location.hostname}:8090/ws`
    const c = new Client({ brokerURL: wsUrl, reconnectDelay: 5000 })
    c.onConnect = () => {
      c.subscribe('/topic/pipeline-progress', (msg) => {
        try {
          const event = JSON.parse(msg.body)
          if (event.counts) {
            dbStatus.value.pending = event.counts.PENDING || 0
            dbStatus.value.processing = event.counts.PROCESSING || 0
            dbStatus.value.completed = event.counts.COMPLETED || 0
            dbStatus.value.failed = event.counts.FAILED || 0
            dbStatus.value.total = dbStatus.value.pending + dbStatus.value.processing + dbStatus.value.completed + dbStatus.value.failed
          }
          if (event.running !== undefined) running.value = event.running
          if (event.speed !== undefined) speed.value = event.speed
          if (event.eta !== undefined) eta.value = event.eta

          if (event.imageId) {
            events.value.unshift(event)
            if (events.value.length > MAX_EVENTS) events.value.length = MAX_EVENTS
            nextTick(() => { if (logContainer.value) logContainer.value.scrollTop = 0 })
          }
        } catch (e) { /* ignore */ }
      })
    }
    c.activate()
    stompClient = c
  }).catch(() => {
    pollTimer = window.setInterval(refreshStatus, 3000)
  })
}

function disconnectWebSocket() {
  if (stompClient) { stompClient.deactivate(); stompClient = null }
  if (pollTimer !== null) { clearInterval(pollTimer); pollTimer = null }
}

onMounted(() => { refreshStatus(); connectWebSocket() })
onUnmounted(() => { disconnectWebSocket() })
</script>

<style scoped>
.pipeline-page { padding: 24px; max-width: 1200px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: center; }
.page-header h2 { margin: 0; font-size: 22px; font-weight: 600; }
.header-meta { display: flex; gap: 20px; font-size: 14px; color: #909399; }
.meta-item { background: #f5f7fa; padding: 4px 12px; border-radius: 4px; }

.status-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.status-card { padding: 24px; border-radius: 10px; text-align: center; color: #fff; position: relative; }
.status-card.pending { background: linear-gradient(135deg, #909399, #b0b3b8); }
.status-card.processing { background: linear-gradient(135deg, #e6a23c, #f0c050); }
.status-card.completed { background: linear-gradient(135deg, #67c23a, #85d65f); }
.status-card.failed { background: linear-gradient(135deg, #f56c6c, #f89898); }
.card-number { font-size: 42px; font-weight: 700; line-height: 1; }
.card-label { font-size: 14px; margin-top: 8px; opacity: 0.9; }
.card-indicator { position: absolute; top: 12px; right: 16px; font-size: 20px; animation: blink 1s infinite; }
@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }
.pulse { animation: pulse-border 1.5s infinite; }
@keyframes pulse-border { 0%,100% { box-shadow: 0 0 0 0 rgba(230,162,60,0.5); } 50% { box-shadow: 0 0 0 12px rgba(230,162,60,0); } }

.progress-section { padding: 20px; background: #f5f7fa; border-radius: 10px; }
.progress-header { display: flex; justify-content: space-between; margin-bottom: 10px; font-size: 14px; }
.progress-text { color: #909399; }

.action-bar { display: flex; gap: 12px; flex-wrap: wrap; }

.event-log { border: 1px solid #e4e7ed; border-radius: 10px; background: #fff; }
.log-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px 0; }
.log-header h3 { margin: 0; font-size: 16px; }
.log-count { font-size: 13px; color: #909399; }
.log-list { max-height: 400px; overflow-y: auto; padding: 8px 20px 20px; }
.log-empty { color: #c0c4cc; text-align: center; padding: 40px 0; font-size: 14px; }
.log-item { display: flex; align-items: center; gap: 10px; padding: 7px 0; border-bottom: 1px solid #f2f2f2; font-size: 13px; }
.log-item:last-child { border-bottom: none; }
.log-time { color: #909399; font-family: monospace; font-size: 12px; min-width: 70px; white-space: nowrap; }
.log-status { flex-shrink: 0; }
.log-file { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.log-error { color: #f56c6c; font-size: 12px; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; }
</style>
