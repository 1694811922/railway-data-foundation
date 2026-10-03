import axios from 'axios'
import { ElMessage } from 'element-plus'

export const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 12000,
})

request.interceptors.response.use((response) => {
  const body = response.data
  if (body?.code !== 0) {
    const message = body?.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(Object.assign(new Error(message), { response }))
  }
  return body.data
}, (error) => {
  // Missing endpoints are handled by src/utils/dataHub.js with a visible demo badge.
  const unavailable = canUseDemo(error)
  if (!unavailable) ElMessage.error(error.response?.data?.message || '服务请求失败')
  return Promise.reject(error)
})

export function canUseDemo(error) {
  const response = error?.response
  // Vite's development proxy returns plain-text HTTP 500 when its backend is offline.
  // The Foundation service returns a JSON API envelope for its own failures.
  return import.meta.env.VITE_USE_MOCK === 'true' || !response || [404, 501, 502, 503, 504].includes(response.status)
    || (response.status === 500 && typeof response.data === 'string')
}
