import client from './client'

export interface TreeNode {
  id: string; label: string; type: 'grade' | 'class' | 'student' | 'face_group' | 'face'
  gradeId?: number; classId?: number; studentId?: number; studentNo?: string
  clusterId?: number; faceCount?: number; faceRecordId?: number
  sampleImages?: string[]; croppedImageUrl?: string; confidence?: number
  children?: TreeNode[]
}

export interface RawEmotionRecord {
  faceRecordId: number; captureTime: string; periodLabel: string
  bbox: string; confidence: number; dominantEmotion: string
  dominantConfidence: number
  croppedImageUrl?: string; imageUrl?: string
  emotions: { happy: number; sad: number; angry: number; surprise: number; fear: number; disgust: number; neutral: number }
}

export function fetchSchoolTree(): Promise<TreeNode[]> {
  return client.get('/school-tree').then(r => r.data as TreeNode[])
}

export function fetchStudentRawEmotions(studentId: number): Promise<RawEmotionRecord[]> {
  return client.get(`/school-tree/student/${studentId}/raw-emotions`).then(r => r.data as RawEmotionRecord[])
}

export function fetchFaceEmotion(faceRecordId: number): Promise<RawEmotionRecord[]> {
  return client.get(`/school-tree/face/${faceRecordId}/emotion`).then(r => r.data as RawEmotionRecord[])
}
