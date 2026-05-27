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
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import client from '@/api/client'
import { ElMessage } from 'element-plus'

const activeTab = ref('grade')
const importing = ref(false)
const importResult = ref<any>(null)

const grades = ref([{ name: '初一', sortOrder: 1 }, { name: '初二', sortOrder: 2 }, { name: '初三', sortOrder: 3 }])
const classes = ref([
  { name: '初一班', sortOrder: 1, vmLibId: null },
  { name: '初二(1)班', sortOrder: 1, vmLibId: 'lib-001' },
  { name: '初二(2)班', sortOrder: 2, vmLibId: null },
])

async function startImport() {
  importing.value = true
  try {
    const res = await client.post('/admin/import', null, { params: { dateDir: './data' } })
    importResult.value = (res as any).data
    ElMessage.success('导入完成')
  } catch {
    ElMessage.error('导入失败')
    importResult.value = { total: 0, imported: 0, failed: 0 }
  } finally { importing.value = false }
}
</script>

<style scoped>
.admin-page h2 { font-size: var(--text-xl); font-weight: 600; margin-bottom: var(--space-4); }
.admin-tabs { background: var(--color-card); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); }
</style>
