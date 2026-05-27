import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ClassDashboardData, SeatHeatmapData } from '@/types'
import { fetchClassDashboard, fetchSeatHeatmap } from '@/api/class'

export const useClassStore = defineStore('class', () => {
  const dashboardData = ref<ClassDashboardData | null>(null)
  const heatmapData = ref<SeatHeatmapData | null>(null)
  const loading = ref(false)
  const currentPeriod = ref('')
  const currentDate = ref('')

  async function loadDashboard(classId: number, params: { date?: string; period_label?: string }) {
    loading.value = true
    try {
      dashboardData.value = await fetchClassDashboard(classId, params)
      currentPeriod.value = params.period_label || ''
      currentDate.value = params.date || ''
    } finally { loading.value = false }
  }

  async function loadHeatmap(classId: number, params: { date?: string; period_label?: string }) {
    loading.value = true
    try { heatmapData.value = await fetchSeatHeatmap(classId, params) }
    finally { loading.value = false }
  }

  function updateFromWs(updates: { student_id: number; dominant_emotion: string; dominant_confidence: number; engagement: number }[]) {
    if (!dashboardData.value) return
    for (const u of updates) {
      const s = dashboardData.value.students.find(s => s.id === u.student_id)
      if (s) { s.dominantEmotion = u.dominant_emotion as any; s.dominantConfidence = u.dominant_confidence; s.engagement = u.engagement }
    }
  }
  return { dashboardData, heatmapData, loading, currentPeriod, currentDate, loadDashboard, loadHeatmap, updateFromWs }
})
