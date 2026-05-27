import client from './client'
import type { StudentProfileData, AlertItem } from '@/types'

export function fetchStudentProfile(studentId: number, params?: { date?: string; period?: string }): Promise<StudentProfileData> {
  return client.get(`/students/${studentId}/emotion-timeline`, { params }).then(r => r.data as StudentProfileData)
}

export function fetchStudentReport(studentId: number, params?: { start?: string; end?: string }): Promise<unknown> {
  return client.get(`/students/${studentId}/emotion-report`, { params }).then(r => r.data)
}

export function fetchStudentAlerts(studentId: number): Promise<AlertItem[]> {
  return client.get(`/students/${studentId}/alerts`).then(r => r.data as AlertItem[])
}

export function createIntervention(data: { student_id: number; action_type: string; description: string; effect?: string }): Promise<void> {
  return client.post('/interventions', data)
}
