<template>
  <div class="engine-manage">
    <h2>引擎管理</h2>

    <el-alert title="VisionMind 引擎状态监控" type="info" show-icon style="margin-bottom: 16px" />

    <div class="engine-cards">
      <el-card v-for="engine in engines" :key="engine.name" class="engine-card">
        <div class="engine-header">
          <el-icon :size="28">
            <component :is="engine.status === 'UP' ? 'Monitor' : 'WarningFilled'" />
          </el-icon>
          <div class="engine-name">{{ engine.name }}</div>
          <el-tag :type="engine.status === 'UP' ? 'success' : 'danger'" size="large">
            {{ engine.status === 'UP' ? '运行中' : '离线' }}
          </el-tag>
        </div>
        <el-descriptions :column="1" border style="margin-top: 16px">
          <el-descriptions-item label="地址">{{ engine.host }}:{{ engine.port }}</el-descriptions-item>
          <el-descriptions-item label="延迟">{{ engine.latencyMs }}ms</el-descriptions-item>
          <el-descriptions-item label="运行时间">{{ engine.uptime }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </div>

    <el-button type="primary" @click="refresh" :loading="loading" style="margin-top: 16px">
      刷新状态
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchEngines } from '@/api/admin'
import { ElMessage } from 'element-plus'
import type { EngineInfo } from '@/api/admin'
import { Monitor, WarningFilled } from '@element-plus/icons-vue'

const loading = ref(false)
const engines = ref<EngineInfo[]>([])

async function refresh() {
  loading.value = true
  try {
    engines.value = await fetchEngines()
  } catch (e: any) {
    ElMessage.error('获取引擎状态失败: ' + (e.message || ''))
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<style scoped>
.engine-manage h2 { font-size: var(--text-xl); font-weight: 600; margin-bottom: var(--space-4); }
.engine-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }
.engine-card { border-radius: var(--radius-md); }
.engine-header { display: flex; align-items: center; gap: 12px; }
.engine-name { font-size: 18px; font-weight: 600; flex: 1; }
</style>
