<template>
  <div class="camera-manage">
    <h2>摄像头管理</h2>

    <el-button type="primary" @click="showDialog = true" style="margin-bottom: 16px">
      + 新增摄像头
    </el-button>

    <el-table :data="cameras" stripe v-loading="loading">
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="ipAddress" label="IP 地址" width="140" />
      <el-table-column prop="classroomName" label="所属教室" width="140" />
      <el-table-column prop="installPosition" label="安装位置" width="100" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ONLINE' ? 'success' : 'danger'" size="small">
            {{ row.status === 'ONLINE' ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="resolution" label="分辨率" width="110" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text @click="editCamera(row)">编辑</el-button>
          <el-button size="small" text type="primary" @click="handleSnapshot(row)">拍照</el-button>
          <el-button size="small" text @click="viewPhotos(row)">照片</el-button>
          <el-button size="small" text type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="showDialog" :title="editing ? '编辑摄像头' : '新增摄像头'" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="IP 地址">
          <el-input v-model="form.ipAddress" />
        </el-form-item>
        <el-form-item label="所属教室">
          <el-select v-model="form.classroomId" placeholder="选择教室" clearable style="width:100%">
            <el-option v-for="c in classrooms" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="安装位置">
          <el-select v-model="form.installPosition" style="width:100%">
            <el-option label="教室前方" value="FRONT" />
            <el-option label="教室后方" value="BACK" />
            <el-option label="教室侧方" value="SIDE" />
          </el-select>
        </el-form-item>
        <el-form-item label="分辨率">
          <el-input v-model="form.resolution" placeholder="2560x1920" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" @click="saveCamera" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showPhotos" title="抓拍记录" width="640px">
      <el-table :data="photos" stripe v-if="photos.length > 0">
        <el-table-column prop="capturedAt" label="抓拍时间" width="180" />
        <el-table-column prop="resolution" label="分辨率" width="110" />
        <el-table-column prop="filePath" label="文件路径" min-width="200">
          <template #default="{ row }">
            <span style="font-size:12px;color:#909399">{{ row.filePath || '-' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无抓拍记录" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchCameras, createCamera, updateCamera, deleteCamera, triggerSnapshot, fetchCameraPhotos } from '@/api/admin'
import client from '@/api/client'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { CameraData } from '@/api/admin'

const loading = ref(false)
const cameras = ref<CameraData[]>([])
const classrooms = ref<any[]>([])
const showDialog = ref(false)
const editing = ref(false)
const saving = ref(false)
const showPhotos = ref(false)
const photos = ref<any[]>([])
const form = ref<CameraData>({
  name: '', ipAddress: '', type: 'IP_CAMERA',
  installPosition: 'FRONT', status: 'ONLINE', resolution: '2560x1920',
  classroomId: undefined, classroomName: ''
})

async function loadCameras() {
  loading.value = true
  try {
    cameras.value = await fetchCameras()
  } catch (e: any) {
    ElMessage.error('加载摄像头列表失败')
  } finally {
    loading.value = false
  }
}

async function loadClassrooms() {
  try {
    const res: any = await client.get('/classrooms')
    classrooms.value = (res as any) || []
  } catch { /* ignore */ }
}

function editCamera(camera: CameraData) {
  editing.value = true
  form.value = { ...camera }
  showDialog.value = true
}

function resetForm() {
  editing.value = false
  form.value = {
    name: '', ipAddress: '', type: 'IP_CAMERA',
    installPosition: 'FRONT', status: 'ONLINE', resolution: '2560x1920',
    classroomId: undefined, classroomName: ''
  }
}

async function saveCamera() {
  saving.value = true
  try {
    const payload = {
      ...form.value,
      classroom: form.value.classroomId ? { id: form.value.classroomId } : null
    }
    if (editing.value && form.value.id) {
      await updateCamera(form.value.id, payload)
      ElMessage.success('更新成功')
    } else {
      await createCamera(payload)
      ElMessage.success('创建成功')
    }
    showDialog.value = false
    resetForm()
    await loadCameras()
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function handleDelete(row: CameraData) {
  try {
    await ElMessageBox.confirm('确定删除该摄像头?', '确认', { type: 'warning' })
    await deleteCamera(row.id!)
    ElMessage.success('已删除')
    await loadCameras()
  } catch { /* cancelled */ }
}

async function handleSnapshot(row: CameraData) {
  try {
    await triggerSnapshot(row.id!)
    ElMessage.success('拍照指令已发送')
  } catch (e: any) {
    ElMessage.error('拍照失败: ' + (e.message || ''))
  }
}

async function viewPhotos(row: CameraData) {
  try {
    photos.value = await fetchCameraPhotos(row.id!)
    showPhotos.value = true
  } catch (e: any) {
    ElMessage.error('加载照片失败')
  }
}

onMounted(() => {
  loadCameras()
  loadClassrooms()
})
</script>

<style scoped>
.camera-manage h2 { font-size: var(--text-xl); font-weight: 600; margin-bottom: var(--space-4); }
</style>
