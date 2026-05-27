import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { StudentProfileData } from '@/types'
import { fetchStudentProfile } from '@/api/student'

export const useStudentStore = defineStore('student', () => {
  const profileData = ref<StudentProfileData | null>(null)
  const loading = ref(false)

  async function loadProfile(studentId: number, params?: { date?: string; period?: string }) {
    loading.value = true
    try { profileData.value = await fetchStudentProfile(studentId, params) }
    finally { loading.value = false }
  }
  return { profileData, loading, loadProfile }
})
