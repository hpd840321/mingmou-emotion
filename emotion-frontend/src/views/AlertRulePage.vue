<template>
  <div class="alert-rule-page">
    <div class="page-header">
      <h2>预警规则管理</h2>
      <el-button type="primary" size="small" @click="showForm = true; form = defaultForm()">新建规则</el-button>
    </div>

    <el-table :data="rules" style="width:100%" stripe>
      <el-table-column prop="name" label="规则名称" width="160" />
      <el-table-column prop="metric" label="指标" width="140">
        <template #default="{ row }">{{ metricLabel(row.metric) }}</template>
      </el-table-column>
      <el-table-column prop="operator" label="条件" width="80" />
      <el-table-column prop="threshold" label="阈值" width="100" />
      <el-table-column prop="scope" label="范围" width="100" />
      <el-table-column prop="durationMin" label="持续(分钟)" width="100" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" text @click="toggleRule(row)">{{ row.enabled ? '禁用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="showForm" title="新建预警规则" width="500px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="规则名称" required>
          <el-input v-model="form.name" placeholder="例如：连续负面情绪预警" />
        </el-form-item>
        <el-form-item label="监控指标" required>
          <el-select v-model="form.metric" style="width:100%">
            <el-option label="负面情绪率(negative_ratio)" value="negative_ratio" />
            <el-option label="正面情绪率(positive_ratio)" value="positive_ratio" />
            <el-option label="课堂参与度(engagement_score)" value="engagement_score" />
          </el-select>
        </el-form-item>
        <el-form-item label="触发条件">
          <el-select v-model="form.operator" style="width:80px">
            <el-option label=">" value=">" /><el-option label=">=" value=">=" />
            <el-option label="<" value="<" /><el-option label="<=" value="<=" />
          </el-select>
          <el-input-number v-model="form.threshold" :min="0" :max="1" :step="0.1" style="margin-left:8px;width:120px" />
        </el-form-item>
        <el-form-item label="持续分钟">
          <el-input-number v-model="form.durationMin" :min="0" :step="5" />
        </el-form-item>
        <el-form-item label="应用范围">
          <el-select v-model="form.scope">
            <el-option label="全校" value="global" />
            <el-option label="年级" value="grade" />
            <el-option label="班级" value="class" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" @click="submitRule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { fetchAlertRules, createAlertRule, type AlertRuleData } from '@/api/admin'
import { ElMessage } from 'element-plus'

const rules = ref<AlertRuleData[]>([])
const showForm = ref(false)
const form = ref<AlertRuleData>(defaultForm())

onMounted(() => loadRules())

function defaultForm(): AlertRuleData {
  return { name: '', metric: 'negative_ratio', operator: '>', threshold: 0.6, scope: 'global', durationMin: 30, enabled: true }
}

async function loadRules() {
  try { rules.value = await fetchAlertRules() }
  catch { /* ignore */ }
}

function metricLabel(m: string): string {
  const labels: Record<string, string> = { negative_ratio: '负面情绪率', positive_ratio: '正面情绪率', engagement_score: '参与度', absence_count: '缺勤数' }
  return labels[m] || m
}

async function submitRule() {
  try {
    await createAlertRule(form.value)
    ElMessage.success('规则创建成功')
    showForm.value = false
    loadRules()
  } catch { ElMessage.error('创建失败') }
}

function toggleRule(row: AlertRuleData) {
  ElMessage.info('启用/禁用功能开发中')
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--space-4); }
.page-header h2 { font-size: var(--text-xl); font-weight: 600; }
</style>
