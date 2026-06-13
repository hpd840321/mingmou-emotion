export const EMOTION_NAMES_CN: Record<string, string> = {
  neutral: '中性',
  happy: '开心',
  sad: '伤心',
  angry: '生气',
  surprise: '惊讶',
  fear: '恐惧',
  disgust: '厌恶',
  contempt: '蔑视',
}

export const EMOTION_ICONS: Record<string, string> = {
  neutral: '😐',
  happy: '😊',
  sad: '😢',
  angry: '😠',
  surprise: '😲',
  fear: '😨',
  disgust: '😖',
  contempt: '🤨',
}

export const EMOTION_COLORS: Record<string, string> = {
  happy: '#22C55E',
  sad: '#F97316',
  angry: '#DC2626',
  surprise: '#F59E0B',
  fear: '#7C3AED',
  disgust: '#374151',
  neutral: '#64748B',
  contempt: '#94A3B8',
}

export function emotionNameCN(key: string): string {
  return EMOTION_NAMES_CN[key] || key
}
