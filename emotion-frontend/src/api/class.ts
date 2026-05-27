import client from './client'
import type { ClassDashboardData, SeatHeatmapData } from '@/types'

export function fetchClassDashboard(classId: number, params: { date?: string; period_label?: string }): Promise<ClassDashboardData> {
  return client.get(`/classes/${classId}/dashboard`, { params }).then(r => r.data as ClassDashboardData)
}

export function fetchClassTrend(classId: number, params: { start?: string; end?: string }): Promise<unknown> {
  return client.get(`/classes/${classId}/emotion-trend`, { params }).then(r => r.data)
}

export function fetchSeatHeatmap(classId: number, params: { date?: string; period_label?: string }): Promise<SeatHeatmapData> {
  return client.get(`/classes/${classId}/heatmap`, { params }).then(r => r.data as SeatHeatmapData)
}
