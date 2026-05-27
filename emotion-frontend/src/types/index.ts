export type UserRole = 'admin' | 'school_manager' | 'grade_leader' | 'teacher' | 'counselor' | 'student' | 'parent'

export interface UserInfo {
  id: number; name: string; role: UserRole;
  gradeId?: number; classId?: number; studentId?: number;
}

export type EmotionType = 'happy' | 'sad' | 'angry' | 'surprise' | 'fear' | 'disgust' | 'neutral'

export interface EmotionDistribution {
  happy: number; sad: number; angry: number; surprise: number; fear: number; disgust: number; neutral: number
}

export interface TimelinePoint extends EmotionDistribution {
  time: string
}

export interface EmotionRecord extends EmotionDistribution {
  dominant_emotion: EmotionType; dominant_confidence: number
}

export interface KpiData {
  label: string; value: number; unit: string; change: number | null;
  changeDirection: 'up' | 'down' | 'flat'; status: 'good' | 'warning' | 'danger' | 'neutral'
}

export interface StudentRow {
  id: number; name: string; studentNo: string; dominantEmotion: EmotionType;
  dominantConfidence: number; happy: number; neutral: number; sad: number; angry: number;
  engagement: number; isAlert: boolean; isAbsent: boolean
}

export interface SeatData {
  row: number; col: number; studentId: number | null; studentName: string;
  studentNo: string; engagement: number | null; dominantEmotion: EmotionType | null;
  isAbsent: boolean; isEmpty: boolean
}

export interface AlertItem {
  id: number; studentId: number; studentName: string; className: string;
  type: string; severity: 'high' | 'medium' | 'low'; message: string;
  timestamp: string; acknowledged: boolean
}

export interface InterventionRecord {
  id: number; studentId: number; teacherName: string; actionType: string;
  description: string; effect: string; createdAt: string
}

export interface ApiResponse<T> { code: number; message: string; data: T }

export interface SchoolOverviewData {
  kpis: KpiData[]
  gradeComparison: { name: string; value: number; classes: { name: string; value: number }[] }[]
  alertRanking: { className: string; rate: number }[]
  trendData: { date: string; value: number; grade: string }[]
  crossClassAlerts: AlertItem[]
}

export interface ClassDashboardData {
  classId: number; className: string; date: string; periodLabel: string;
  kpis: KpiData[]; timelineData: { time: string } & EmotionDistribution[];
  students: StudentRow[]; totalPages: number
}

export interface SeatHeatmapData {
  seats: SeatData[]; rows: number; cols: number;
  distribution: { label: string; count: number; pct: number }[]
  lowEngagementAlerts: { studentName: string; seatInfo: string; consecutiveClasses: number; desc: string }[]
}

export interface StudentProfileData {
  studentId: number; studentName: string; studentNo: string; className: string;
  tags: string[]; kpis: KpiData[];
  trendData: { date: string } & EmotionDistribution[];
  weekDistribution: EmotionDistribution;
  periodComparison: { period: string; value: number }[]
  alertTimeline: { date: string; period: string; desc: string; triggerValue: number }[]
  interventions: InterventionRecord[]
}

export interface WsEmotionUpdate {
  type: 'emotion_update'; class_id: number; timestamp: string;
  updates: { student_id: number; dominant_emotion: string; dominant_confidence: number; engagement: number }[]
}

export interface WsAlert {
  type: 'alert'; alert_id: number; student_name: string; class_name: string;
  message: string; severity: 'high' | 'medium' | 'low'; timestamp: string
}
