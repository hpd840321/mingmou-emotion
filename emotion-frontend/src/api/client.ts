import axios from 'axios'
import type { ApiResponse } from '@/types'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
})

client.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown>
    if (body.code !== 0) return Promise.reject(new Error(body.message || 'API error'))
    response.data = body.data
    return response
  },
  (error) => {
    if (error.response?.status === 401) window.location.href = '/login'
    return Promise.reject(error)
  }
)

export default client
