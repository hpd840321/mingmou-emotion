<template>
  <div class="face-cluster-page">
    <div class="page-header">
      <h2>人脸聚类标注</h2>
      <div class="header-actions">
        <el-select v-model="classId" placeholder="选择班级" @change="loadData" size="small">
          <el-option label="初一班" :value="1" />
          <el-option label="初二(1)班" :value="2" />
          <el-option label="初二(2)班" :value="3" />
        </el-select>
        <el-tag :type="clusters.length > 0 ? 'warning' : 'success'">
          待标注: {{ clusters.length }}
        </el-tag>
      </div>
    </div>

    <el-table :data="clusters" style="width:100%" stripe>
      <el-table-column label="学生姓名" width="130">
        <template #default="{ row }">
          <span :style="{ color: row.autoAnnotated ? '#909399' : '#409EFF' }">
            {{ row.studentName || (row.autoAnnotated ? '未命名' : '未标注') }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="学号" width="150">
        <template #default="{ row }">
          {{ row.studentNo || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="sampleCount" label="出现次数" width="100" sortable />
      <el-table-column label="首次出现" width="160">
        <template #default="{ row }">{{ formatTime(row.firstSeenAt) }}</template>
      </el-table-column>
      <el-table-column label="最近出现" width="160">
        <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openRename(row)">重命名</el-button>
          <el-button size="small" @click="openMerge(row)">合并</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="showRename" title="重命名学生" width="360px">
      <el-form :model="renameForm" label-width="80px">
        <el-form-item label="当前名称">
          <el-input :model-value="renameForm.currentName" disabled />
        </el-form-item>
        <el-form-item label="新名称" required>
          <el-input v-model="renameForm.newName" placeholder="输入真实姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showRename = false">取消</el-button>
        <el-button type="primary" @click="submitRename">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchClusters, renameCluster, type FaceClusterVO } from '@/api/admin'
import { ElMessage } from 'element-plus'

const clusters = ref<FaceClusterVO[]>([])
const classId = ref(1)
const showRename = ref(false)
const selectedCluster = ref<FaceClusterVO | null>(null)
const renameForm = ref({ currentName: '', newName: '' })

onMounted(() => loadData())

async function loadData() {
  try {
    clusters.value = await fetchClusters(classId.value)
  } catch { /* ignore */ }
}

function formatTime(ts: string) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}

function openRename(cluster: FaceClusterVO) {
  selectedCluster.value = cluster
  renameForm.value = {
    currentName: cluster.studentName || '未命名',
    newName: cluster.studentName || ''
  }
  showRename.value = true
}

async function submitRename() {
  if (!selectedCluster.value || !renameForm.value.newName.trim()) {
    ElMessage.warning('请输入新名称')
    return
  }
  try {
    await renameCluster(selectedCluster.value.id, renameForm.value.newName.trim())
    ElMessage.success('重命名成功')
    showRename.value = false
    loadData()
  } catch { ElMessage.error('重命名失败') }
}

function openMerge(cluster: FaceClusterVO) {
  ElMessage.info('合并功能开发中')
}
</script>

<style scoped>
.face-cluster-page { padding: 0; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-4); }
.page-header h2 { font-size: var(--text-xl); font-weight: 600; }
.header-actions { display: flex; gap: var(--space-3); align-items: center; }
</style>
