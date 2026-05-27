import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { AlertItem } from '@/types'

export const useAlertStore = defineStore('alert', () => {
  const alerts = ref<AlertItem[]>([])
  const unreadCount = computed(() => alerts.value.filter(a => !a.acknowledged).length)
  const recentAlerts = computed(() => alerts.value.filter(a => !a.acknowledged).slice(0, 5))

  function addAlert(alert: AlertItem) {
    alerts.value.unshift(alert)
    if (alerts.value.length > 100) alerts.value.pop()
  }
  function markRead(id: number) {
    const alert = alerts.value.find(a => a.id === id)
    if (alert) alert.acknowledged = true
  }
  function markAllRead() { alerts.value.forEach(a => { a.acknowledged = true }) }
  return { alerts, unreadCount, recentAlerts, addAlert, markRead, markAllRead }
})
