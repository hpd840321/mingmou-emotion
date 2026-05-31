import client from './client'

export interface FaceClusterVO {
  id: number; classId: number; className?: string; sampleCount: number
  firstSeenAt: string; lastSeenAt: string; periodLabels?: string[]; sampleImages?: string[]
  studentId?: number; studentName?: string; studentNo?: string; autoAnnotated?: boolean
}

export interface AnnotateRequest {
  studentName: string; studentNo: string; classId: number
}

export function fetchClusters(classId: number, status = 'auto_annotated,renamed'): Promise<FaceClusterVO[]> {
  return client.get('/face-clusters', { params: { class_id: classId, status } }).then(r => r.data as FaceClusterVO[])
}

export function annotateCluster(id: number, data: AnnotateRequest): Promise<void> {
  return client.post(`/face-clusters/${id}/annotate`, data)
}

export function mergeCluster(id: number, studentId: number): Promise<void> {
  return client.post(`/face-clusters/${id}/merge`, { studentId })
}

export function renameCluster(id: number, studentName: string): Promise<void> {
  return client.post(`/face-clusters/${id}/rename`, { studentName })
}

export interface AlertRuleData {
  id?: number; name: string; scope: string; scopeId?: number
  metric: string; operator: string; threshold: number; durationMin?: number; enabled?: boolean
}

export function fetchAlertRules(): Promise<AlertRuleData[]> {
  return client.get('/alert-rules').then(r => r.data as AlertRuleData[])
}

export function createAlertRule(rule: AlertRuleData): Promise<AlertRuleData> {
  return client.post('/alert-rules', rule).then(r => r.data as AlertRuleData)
}
