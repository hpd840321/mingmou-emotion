import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserInfo, UserRole } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>({
    id: 1, name: '管理员', role: 'admin',
  })

  const isLoggedIn = computed(() => user.value !== null)
  const role = computed(() => user.value?.role ?? null)

  function setUser(u: UserInfo | null) { user.value = u }

  function switchRole(role: UserRole) {
    const names: Record<UserRole, string> = {
      admin: '管理员', school_manager: '校领导', grade_leader: '年级组长',
      teacher: '班主任', counselor: '心理老师', student: '学生', parent: '家长',
    }
    user.value = { id: 1, name: names[role], role }
  }

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

  return { user, isLoggedIn, role, setUser, switchRole, hasRole, canViewStudent, canViewClass }
})
