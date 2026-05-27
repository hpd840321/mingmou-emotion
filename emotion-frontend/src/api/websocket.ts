import { io, Socket } from 'socket.io-client'
import type { WsEmotionUpdate, WsAlert } from '@/types'

let classSocket: Socket | null = null
let alertSocket: Socket | null = null

export function connectClassSocket(classId: number, onUpdate: (data: WsEmotionUpdate) => void): Socket {
  if (classSocket) classSocket.disconnect()
  classSocket = io(`/ws/class/${classId}/emotion`, {
    transports: ['websocket'],
    reconnectionDelayMax: 30000,
  })
  classSocket.on('message', onUpdate)
  classSocket.on('connect_error', (err) => console.error('Class WS error:', err.message))
  return classSocket
}

export function disconnectClassSocket(): void {
  classSocket?.disconnect()
  classSocket = null
}

export function connectAlertSocket(onAlert: (data: WsAlert) => void): Socket {
  if (alertSocket) alertSocket.disconnect()
  alertSocket = io('/ws/alerts', {
    transports: ['websocket'],
    reconnectionDelayMax: 30000,
  })
  alertSocket.on('message', onAlert)
  alertSocket.on('connect_error', (err) => console.error('Alert WS error:', err.message))
  return alertSocket
}

export function disconnectAlertSocket(): void {
  alertSocket?.disconnect()
  alertSocket = null
}
