import client from './client'
import type { SchoolOverviewData, AlertItem } from '@/types'

export function fetchSchoolOverview(params?: { grade_id?: number; period?: string }): Promise<SchoolOverviewData> {
  return client.get('/school/overview', { params }).then(r => r.data as SchoolOverviewData)
}

export function fetchSchoolAlerts(params?: { status?: string }): Promise<AlertItem[]> {
  return client.get('/school/alerts', { params }).then(r => r.data as AlertItem[])
}
