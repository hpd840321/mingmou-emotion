<template>
  <div class="pipeline-page">
    <!-- Header -->
    <div class="page-header">
      <div>
        <h2>处理管线监控</h2>
        <p class="page-desc">实时查看 data 目录图片处理进度与状态</p>
      </div>
      <div class="header-meta" v-if="speed > 0 || eta">
        <el-tag v-if="speed > 0" size="small" effect="plain">处理速度 {{ speed }} 张/秒</el-tag>
        <el-tag v-if="eta" size="small" type="warning" effect="plain">预计剩余 {{ eta }}</el-tag>
      </div>
    </div>

    <!-- Status cards -->
    <el-row :gutter="16" class="status-row">
      <el-col :span="6"><div class="stat-card pending"><div class="stat-num">{{ dbStatus.pendingReal }}</div><div class="stat-label">待处理</div></div></el-col>
      <el-col :span="6"><div class="stat-card processing" :class="{ pulse: running }"><div class="stat-num">{{ dbStatus.processing }}</div><div class="stat-label">处理中</div><div v-if="running" class="stat-dot" /></div></el-col>
      <el-col :span="6"><div class="stat-card completed"><div class="stat-num">{{ dbStatus.completed }}</div><div class="stat-label">已完成 / {{ dbStatus.totalFiles }}</div></div></el-col>
      <el-col :span="6"><div class="stat-card failed"><div class="stat-num">{{ dbStatus.failed }}</div><div class="stat-label">失败</div></div></el-col>
    </el-row>

    <!-- Progress -->
    <el-card shadow="never" class="section-card">
      <div class="section-row">
        <span class="section-label">总进度</span>
        <span class="section-value">{{ dbStatus.completed + dbStatus.failed }} / {{ dbStatus.totalFiles }} ({{ progressPercent }}%)</span>
      </div>
      <el-progress :percentage="progressPercent" :status="progressStatus" :stroke-width="20" />
    </el-card>

    <!-- Actions -->
    <div class="action-bar">
      <el-button @click="refreshStatus" :loading="refreshing" plain>刷新状态</el-button>
      <el-button v-if="!running" type="success" @click="startPipeline" :icon="VideoPlay">启动管线</el-button>
      <el-button v-else type="danger" @click="stopPipeline" :icon="VideoPause">停止处理</el-button>
      <el-button type="warning" plain @click="resetFailed" :disabled="dbStatus.failed === 0">重新处理失败 ({{ dbStatus.failed }})</el-button>
      <el-button text @click="refreshTree" :loading="treeLoading">刷新目录树</el-button>
    </div>

    <!-- Tree + Log two-panel layout (matching SchoolTree) -->
    <div class="content-split">
      <div class="tree-panel">
        <div class="tree-panel-header"><span>data 目录结构</span></div>
        <el-tree v-if="treeData.length > 0" :data="treeData" :props="treeProps" node-key="id" :key="treeKey" highlight-current @node-click="onTreeClick">
          <template #default="{ data }">
            <span class="tree-node">
              <span class="node-icon" :class="iconFor(data)"></span>
              <span class="node-name">{{ data.name }}</span>
              <span v-if="data.count !== undefined" class="node-count">{{ data.count }}</span>
              <span v-if="data.PENDING > 0" class="tag-dot pending" />
              <span v-if="data.COMPLETED > 0" class="tag-dot completed" />
              <span v-if="data.FAILED > 0" class="tag-dot failed" />
            </span>
          </template>
        </el-tree>
        <el-empty v-else :image-size="60" description="暂无目录数据" />
      </div>
      <div class="detail-panel">
        <!-- Node detail -->
        <div v-if="selectedTreeNode" class="node-detail">
          <h3>{{ selectedTreeNode.name }}</h3>
          <p class="node-path">{{ selectedTreeNode._path }}</p>
          <div class="detail-stats">
            <div class="dstat pending">待处理 {{ selectedTreeNode.PENDING || 0 }}</div>
            <div class="dstat processing">处理中 {{ selectedTreeNode.PROCESSING || 0 }}</div>
            <div class="dstat completed">已完成 {{ selectedTreeNode.COMPLETED || 0 }}</div>
            <div class="dstat failed">失败 {{ selectedTreeNode.FAILED || 0 }}</div>
          </div>
          <div class="detail-progress">
            <el-progress :percentage="detailPercent" :stroke-width="16" />
          </div>
        </div>
        <div v-else class="detail-empty">
          <p>从左侧目录树选择节点查看详情</p>
        </div>
        <!-- Event log -->
        <div class="event-log-section">
          <div class="log-header">实时日志 ({{ events.length }})</div>
          <div class="log-list" ref="logContainer">
            <div v-if="events.length === 0" class="log-empty">暂无事件，启动管线后显示</div>
            <div v-for="(evt, i) in events" :key="i" class="log-item" :class="{ 'is-error': evt.newStatus === 'FAILED' }" @click="showErrorDetail(evt)">
              <span class="log-time">{{ formatTime(evt.timestamp) }}</span>
              <span class="log-tag-mini" :class="evt.newStatus">{{ evt.newStatus }}</span>
              <span class="log-file">{{ evt.fileName }}</span>
              <span v-if="evt.errorMessage" class="log-err-text" :title="evt.errorMessage">{{ evt.errorMessage }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import client from '@/api/client'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VideoPlay, VideoPause } from '@element-plus/icons-vue'

const refreshing = ref(false)
const running = ref(false)
const speed = ref(0)
const eta = ref('')
const logContainer = ref<HTMLElement | null>(null)
const events = ref<any[]>([])
const MAX_EVENTS = 300
const treeData = ref<any[]>([])
const treeLoading = ref(false)
const treeKey = ref(0)
const treeProps = { children: 'children', label: 'name' }
const selectedTreeNode = ref<any>(null)

const dbStatus = ref({ pending: 0, processing: 0, completed: 0, failed: 0, total: 0, totalFiles: 0, pendingReal: 0 })

const progressPercent = computed(() => {
  const total = dbStatus.value.totalFiles || dbStatus.value.total
  if (total === 0) return 0
  return Math.round(((dbStatus.value.completed + dbStatus.value.failed) / total) * 100)
})
const progressStatus = computed(() => {
  const total = dbStatus.value.totalFiles || dbStatus.value.total
  if (total === 0) return undefined
  if (progressPercent.value === 100) return 'success'
  return undefined
})

const detailPercent = computed(() => {
  if (!selectedTreeNode.value) return 0
  const t = (selectedTreeNode.value.COMPLETED||0) + (selectedTreeNode.value.FAILED||0)
  const total = (selectedTreeNode.value.count || t)
  return total > 0 ? Math.round(t / total * 100) : 0
})

function onTreeClick(data: any) {
  selectedTreeNode.value = data
}

function iconFor(data: any): string {
  if (data.type === 'period') return 'icon-file'
  if (data.type === 'date') return 'icon-folder'
  if (data.type === 'class') return 'icon-building'
  return 'icon-home'
}
function formatTime(ts: string) {
  if (!ts) return ''
  return new Date(ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function pickStatus(data: any): any {
  return { PENDING: data.PENDING||0, PROCESSING: data.PROCESSING||0, COMPLETED: data.COMPLETED||0, FAILED: data.FAILED||0 }
}
let nodeIdCounter = 0
function normalizeTree(schools: any[], parentPath = ''): any[] {
  return schools.map((s: any) => {
    const path = parentPath ? parentPath + ' / ' + s.name : s.name
    return {
      id: ++nodeIdCounter, type: 'school',
      ...pickStatus(s), name: s.name, count: s.count, _path: path,
      children: (s.classes || []).map((c: any) => {
        const cp = path + ' / ' + c.name
        return { id: ++nodeIdCounter, type: 'class', ...pickStatus(c), name: c.name, count: c.count, _path: cp,
          children: (c.dates || []).map((d: any) => {
            const dp = cp + ' / ' + d.name
            return { id: ++nodeIdCounter, type: 'date', ...pickStatus(d), name: d.name, count: d.count, _path: dp,
              children: (d.periods || []).map((p: any) => ({ id: ++nodeIdCounter, type: 'period', ...pickStatus(p), name: p.name, count: p.count, _path: dp + ' / ' + p.name })),
            }
          }),
        }
      }),
    }
  })
}

async function refreshTree() {
  treeLoading.value = true
  try {
    const res: any = await client.get('/admin/pipeline/data-dirs')
    const d = res.data || res
    treeData.value = normalizeTree(d.schools || [])
    console.log(`[Pipeline] Tree loaded: ${treeData.value.length} schools`)
  } catch (e: any) {
    console.error('[Pipeline] Tree load failed:', e.message)
  }
  finally { treeLoading.value = false; treeKey.value++ }
}

async function refreshStatus() {
  refreshing.value = true
  try {
    const res: any = await client.get('/admin/pipeline/status')
    const d = res.data || res
    console.log('[Pipeline] Status loaded:', d.totalFiles, 'files')
    Object.assign(dbStatus.value, d)
  if (d.running !== undefined) running.value = d.running
  if (d.speed !== undefined) speed.value = d.speed
  if (d.eta !== undefined) eta.value = d.eta
  if (d.totalFiles !== undefined) dbStatus.value.totalFiles = d.totalFiles
  if (d.pendingReal !== undefined) dbStatus.value.pendingReal = d.pendingReal
  } catch (e: any) { console.error(e) }
  finally { refreshing.value = false }
}

function showErrorDetail(evt: any) {
  if (evt.newStatus !== 'FAILED' || !evt.errorMessage) return
  ElMessageBox.alert(
    `<div style="margin-bottom:8px"><b>文件:</b> ${evt.fileName || '未知'}</div>
     <div style="margin-bottom:8px"><b>时间:</b> ${formatTime(evt.timestamp)}</div>
     <div><b>错误:</b> ${evt.errorMessage}</div>`,
    '处理失败详情',
    { dangerouslyUseHTMLString: true, confirmButtonText: '关闭', type: 'error' }
  )
}

async function startPipeline() {
  events.value = []; running.value = true
  try { await client.post('/admin/pipeline/run'); ElMessage.success('管线已启动') }
  catch (e: any) { ElMessage.error('启动失败'); running.value = false }
  refreshTree()
}
async function stopPipeline() {
  try { await client.post('/admin/pipeline/stop'); ElMessage.success('停止信号已发送') }
  catch { ElMessage.error('发送停止信号失败') }
}
async function resetFailed() {
  if (dbStatus.value.failed === 0) return
  try {
    await ElMessageBox.confirm(`确定将 ${dbStatus.value.failed} 张失败图片重置为待处理?`, '确认', { type: 'warning' })
    const res: any = await client.post('/admin/pipeline/reset-failed')
    const d = res.data || res
    ElMessage.success(d.message || '重置完成')
    await refreshStatus(); refreshTree()
  } catch (e: any) { if (e !== 'cancel') ElMessage.error('重置失败') }
}

// WebSocket
let stompClient: any = null
let pollTimer: number | null = null
function connectWebSocket() {
  import('@stomp/stompjs').then(({ Client }) => {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const c = new Client({ brokerURL: `${proto}//${window.location.host}/ws`, reconnectDelay: 5000 })
    c.onConnect = () => c.subscribe('/topic/pipeline-progress', (msg) => {
      try {
        const evt = JSON.parse(msg.body)
        if (evt.counts) {
          dbStatus.value.processing = evt.counts.PROCESSING || 0
          dbStatus.value.completed = evt.counts.COMPLETED || 0
          dbStatus.value.failed = evt.counts.FAILED || 0
          dbStatus.value.pendingReal = Math.max(0, dbStatus.value.totalFiles - dbStatus.value.completed - dbStatus.value.failed)
        }
        if (evt.running !== undefined) running.value = evt.running
        if (evt.speed !== undefined) speed.value = evt.speed
        if (evt.eta !== undefined) eta.value = evt.eta
        if (evt.imageId) {
          events.value.unshift(evt)
          if (events.value.length > MAX_EVENTS) events.value.length = MAX_EVENTS
          nextTick(() => { if (logContainer.value) logContainer.value.scrollTop = 0 })
        }
      } catch { /* ignore */ }
    })
    c.activate(); stompClient = c
  }).catch((e: any) => {
    console.warn('[Pipeline] STOMP unavailable, fallback to polling:', e.message || e)
    pollTimer = window.setInterval(refreshStatus, 3000)
  })
}
function disconnectWebSocket() {
  if (stompClient) { stompClient.deactivate(); stompClient = null }
  if (pollTimer !== null) { clearInterval(pollTimer); pollTimer = null }
}

let treeTimer: number | null = null
onMounted(() => {
  refreshStatus(); refreshTree(); connectWebSocket()
  treeTimer = window.setInterval(() => {
    if (running.value) refreshTree()
  }, 10000)
})
onUnmounted(() => {
  disconnectWebSocket()
  if (treeTimer !== null) { clearInterval(treeTimer); treeTimer = null }
})
</script>

<style scoped>
.pipeline-page { padding: 24px; max-width: 1440px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; }
.page-header h2 { margin: 0; font-size: 22px; font-weight: 600; }
.page-desc { margin: 4px 0 0; font-size: 13px; color: #909399; }
.header-meta { display: flex; gap: 8px; flex-shrink: 0; }

/* Status cards - matching SchoolTree theme */
.status-row { margin-bottom: 0 !important; }
.stat-card { padding: 20px; border-radius: 10px; text-align: center; color: #fff; position: relative; }
.stat-card.pending { background: linear-gradient(135deg, #909399, #b0b3b8); }
.stat-card.processing { background: linear-gradient(135deg, #e6a23c, #f0c050); }
.stat-card.completed { background: linear-gradient(135deg, #67c23a, #85d65f); }
.stat-card.failed { background: linear-gradient(135deg, #f56c6c, #f89898); }
.stat-num { font-size: 38px; font-weight: 700; line-height: 1; }
.stat-label { font-size: 13px; margin-top: 6px; opacity: .85; }
.stat-dot { position: absolute; top: 10px; right: 14px; width: 10px; height: 10px; border-radius: 50%; background: #fff; animation: blink 1s infinite; }
@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: .3; } }
.pulse { animation: pulse-border 1.5s infinite; }
@keyframes pulse-border { 0%,100% { box-shadow: 0 0 0 0 rgba(230,162,60,.5); } 50% { box-shadow: 0 0 0 12px rgba(230,162,60,0); } }

/* Section card */
.section-card { border-radius: 10px; }
.section-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 14px; }
.section-value { color: #909399; }

/* Action bar */
.action-bar { display: flex; gap: 8px; flex-wrap: wrap; }

/* Tree + Detail split layout — matching SchoolTree */
.content-split { display: flex; gap: 16px; height: 520px; }
.tree-panel { width: 340px; flex-shrink: 0; background: #fff; border: 1px solid #e4e7ed; border-radius: 10px; padding: 12px; overflow-y: auto; }
.tree-panel-header { font-size: 14px; font-weight: 600; margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px solid #f0f0f0; }
.detail-panel { flex: 1; background: #fff; border: 1px solid #e4e7ed; border-radius: 10px; padding: 16px; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; }

/* Tree node — matching SchoolTree */
.tree-node { display: flex; align-items: center; gap: 6px; width: 100%; font-size: 13px; }
.node-icon { display: inline-block; width: 14px; height: 14px; border-radius: 3px; flex-shrink: 0; }
.node-icon.icon-home { background: #3b82f6; }
.node-icon.icon-home::after { content: ''; display: block; margin: 3px auto 0; width: 8px; height: 6px; background: #fff; clip-path: polygon(50% 0%, 0% 100%, 100% 100%); }
.node-icon.icon-building { background: #8b5cf6; }
.node-icon.icon-building::after { content: ''; display: block; margin: 2px auto; width: 8px; height: 8px; border: 2px solid #fff; border-top: none; border-radius: 0 0 2px 2px; }
.node-icon.icon-folder { background: #f59e0b; }
.node-icon.icon-folder::after { content: ''; display: block; margin: 2px auto; width: 8px; height: 7px; background: #fff; border-radius: 1px; }
.node-icon.icon-file { background: #10b981; }
.node-icon.icon-file::after { content: ''; display: block; margin: 3px auto; width: 5px; height: 7px; background: #fff; border-radius: 1px; }
.node-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-count { font-size: 11px; color: #909399; background: #f5f7fa; padding: 0 6px; border-radius: 3px; }
.tag-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.tag-dot.pending { background: #d1d5db; }
.tag-dot.completed { background: #22c55e; }
.tag-dot.failed { background: #ef4444; }

/* Node detail panel */
.node-detail h3 { margin: 0 0 4px; font-size: 16px; }
.node-path { font-size: 12px; color: #909399; margin: 0 0 12px; }
.detail-stats { display: flex; gap: 8px; flex-wrap: wrap; }
.dstat { padding: 6px 12px; border-radius: 6px; font-size: 12px; font-weight: 600; }
.dstat.pending { background: #f3f4f6; color: #6b7280; }
.dstat.processing { background: #fef3c7; color: #d97706; }
.dstat.completed { background: #d1fae5; color: #059669; }
.dstat.failed { background: #fee2e2; color: #dc2626; }
.detail-progress { margin-top: 8px; }
.detail-empty { flex: 1; display: flex; align-items: center; justify-content: center; color: #c0c4cc; font-size: 14px; }

/* Event log inside detail panel */
.event-log-section { flex: 1; display: flex; flex-direction: column; min-height: 0; border-top: 1px solid #f0f0f0; padding-top: 8px; }
.log-header { font-size: 13px; font-weight: 600; margin-bottom: 6px; }
.log-list { flex: 1; overflow-y: auto; max-height: 180px; }
.log-empty { text-align: center; color: #c0c4cc; padding: 30px 0; font-size: 13px; }
.log-item { display: flex; align-items: center; gap: 6px; padding: 4px 0; border-bottom: 1px solid #f8f8f8; font-size: 12px; cursor: pointer; }
.log-item:last-child { border-bottom: none; }
.log-item:hover { background: #fafafa; }
.log-time { color: #909399; font-family: monospace; font-size: 11px; min-width: 55px; white-space: nowrap; }
.log-tag-mini { font-size: 10px; padding: 1px 5px; border-radius: 3px; font-weight: 600; flex-shrink: 0; }
.log-tag-mini.PENDING { background: #e5e7eb; color: #6b7280; }
.log-tag-mini.PROCESSING { background: #fef3c7; color: #d97706; }
.log-tag-mini.COMPLETED { background: #d1fae5; color: #059669; }
.log-tag-mini.FAILED { background: #fee2e2; color: #dc2626; }
.log-file { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.log-err-text { color: #dc2626; font-size: 11px; max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; cursor: pointer; }
.log-err-text:hover { color: #991b1b; text-decoration: underline; }
.log-item.is-error { background: #fef2f2; border-radius: 3px; padding: 4px 6px; margin: 2px -6px; }
</style>
