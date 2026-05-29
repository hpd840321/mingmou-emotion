import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { UserRole } from '@/types'
import { login as loginApi, fetchMe } from '@/api/auth'

const TOKEN_KEY = 'auth_token'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<{ username: string; name: string; role: UserRole; gradeId?: number; classId?: number } | null>(null)
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))

  const isLoggedIn = computed(() => !!token.value)
  const role = computed(() => user.value?.role ?? null)

  async function login(username: string, password: string) {
    const res = await loginApi(username, password)
    token.value = res.token
    localStorage.setItem(TOKEN_KEY, res.token)
    user.value = {
      username: res.user.username,
      name: res.user.name,
      role: res.user.role as UserRole,
      gradeId: res.user.gradeId,
      classId: res.user.classId,
    }
  }

  async function restoreSession() {
    if (!token.value) return false
    try {
      const info = await fetchMe()
      user.value = {
        username: info.username,
        name: info.name,
        role: info.role as UserRole,
        gradeId: info.gradeId,
        classId: info.classId,
      }
      return true
    } catch {
      logout()
      return false
    }
  }

  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  function hasRole(roles: UserRole[]): boolean {
    if (!user.value) return false
    return roles.includes(user.value.role)
  }

  function canViewStudent(studentId: number): boolean {
    if (!user.value) return false
    if (['admin', 'counselor'].includes(user.value.role)) return true
    return user.value.classId === studentId
  }

  function canViewClass(classId: number): boolean {
    if (!user.value) return false
    if (['admin', 'counselor'].includes(user.value.role)) return true
    return user.value.classId === classId
  }

  return { user, token, isLoggedIn, role, login, logout, restoreSession, hasRole, canViewStudent, canViewClass }
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
