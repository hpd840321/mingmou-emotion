import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserInfo, UserRole } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(null)
  const isLoggedIn = computed(() => user.value !== null)
  const role = computed(() => user.value?.role ?? null)

  function setUser(u: UserInfo) { user.value = u }
  function hasRole(roles: UserRole[]): boolean {
    if (!user.value) return false
    return roles.includes(user.value.role)
  }
  function canViewStudent(studentId: number): boolean {
    if (!user.value) return false
    if (['admin', 'counselor'].includes(user.value.role)) return true
    return user.value.studentId === studentId
  }
  function canViewClass(classId: number): boolean {
    if (!user.value) return false
    if (['admin', 'counselor'].includes(user.value.role)) return true
    return user.value.classId === classId
  }
  return { user, isLoggedIn, role, setUser, hasRole, canViewStudent, canViewClass }
})
