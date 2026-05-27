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
        <el-tag type="warning">待标注: {{ clusters.length }}</el-tag>
      </div>
    </div>

    <el-table :data="clusters" style="width:100%" stripe @row-click="openAnnotate">
      <el-table-column label="预览" width="80">
        <template #default="{ row }">
          <el-avatar :size="40" icon="UserFilled" />
        </template>
      </el-table-column>
      <el-table-column prop="sampleCount" label="出现次数" width="100" sortable />
      <el-table-column label="首次出现" width="180">
        <template #default="{ row }">{{ formatTime(row.firstSeenAt) }}</template>
      </el-table-column>
      <el-table-column label="最近出现" width="180">
        <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
      </el-table-column>
      <el-table-column prop="periodLabels" label="时段分布">
        <template #default="{ row }">
          <el-tag v-for="p in (row.periodLabels || ['早读','第1节','第2节']).slice(0,3)" :key="p" size="small" style="margin-right:4px">{{ p }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click.stop="openAnnotate(row)">标注</el-button>
          <el-button size="small" @click.stop="openMerge(row)">合并</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 标注对话框 -->
    <el-dialog v-model="showAnnotate" title="人脸标注" width="400px">
      <el-form :model="annotateForm" label-width="80px">
        <el-form-item label="姓名" required>
          <el-input v-model="annotateForm.studentName" />
        </el-form-item>
        <el-form-item label="学号" required>
          <el-input v-model="annotateForm.studentNo" />
        </el-form-item>
        <el-form-item label="班级">
          <el-input :model-value="'初一班'" disabled />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAnnotate = false">取消</el-button>
        <el-button type="primary" @click="submitAnnotate">确认标注</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchClusters, annotateCluster, type FaceClusterVO, type AnnotateRequest } from '@/api/admin'
import { ElMessage } from 'element-plus'

const clusters = ref<FaceClusterVO[]>([])
const classId = ref(1)
const showAnnotate = ref(false)
const selectedCluster = ref<FaceClusterVO | null>(null)
const annotateForm = ref<AnnotateRequest>({ studentName: '', studentNo: '', classId: 1 })

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

function openAnnotate(cluster: FaceClusterVO) {
  selectedCluster.value = cluster
  annotateForm.value = { studentName: '', studentNo: '', classId: classId.value }
  showAnnotate.value = true
}

async function submitAnnotate() {
  if (!selectedCluster.value) return
  try {
    await annotateCluster(selectedCluster.value.id, annotateForm.value)
    ElMessage.success('标注成功')
    showAnnotate.value = false
    loadData()
  } catch { ElMessage.error('标注失败') }
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
