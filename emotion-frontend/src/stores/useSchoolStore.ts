import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { SchoolOverviewData } from '@/types'
import { fetchSchoolOverview } from '@/api/school'

export const useSchoolStore = defineStore('school', () => {
  const overviewData = ref<SchoolOverviewData | null>(null)
  const loading = ref(false)

  async function loadOverview(params?: { grade_id?: number; period?: string }) {
    loading.value = true
    try { overviewData.value = await fetchSchoolOverview(params) }
    finally { loading.value = false }
  }
  return { overviewData, loading, loadOverview }
})
