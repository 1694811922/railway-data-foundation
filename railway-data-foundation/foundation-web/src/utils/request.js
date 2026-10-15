import axios from 'axios'
import { ElMessage } from 'element-plus'
import { session, clearSession, explicitDemo } from './sessionState'
import { downloadFile, downloadErrorBody } from './download'

export const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 12000,
  withCredentials: true,
})

request.interceptors.request.use(config=>{
  if(explicitDemo)throw new Error('显式演示模式禁止请求真实系统')
  config.identityEpoch=session.epoch
  if(!['get','head','options'].includes(config.method)&&session.csrf)config.headers.set(session.csrf.header_name,session.csrf.token)
  return config
})

request.interceptors.response.use((response) => {
  if(response.config.identityEpoch!==session.epoch)throw new Error('登录身份已变化，旧响应已丢弃')
  if(response.config.download)return downloadFile(response)
  const body = response.data
  if (body?.code !== 0) {
    const message = body?.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(Object.assign(new Error(message), { response }))
  }
  return body.data
}, async(error) => {
  if(error.config?.download&&error.response)error.response.data=await downloadErrorBody(error.response.data)
  if(error.config?.identityEpoch!==undefined&&error.config.identityEpoch!==session.epoch)return Promise.reject(new Error('登录身份已变化，旧响应已丢弃'))
  if(error.response?.status===401&&error.config?.url!=='/auth/login'){
    const hadIdentity = !!session.user
    clearSession();if(hadIdentity)window.dispatchEvent(new Event('foundation-session-expired'))
  }
  // Real errors are never converted into demo successes.
  const unavailable = canUseDemo(error)
  if (!unavailable&&!error.config?.quiet) ElMessage.error(error.response?.data?.message || '服务请求失败')
  return Promise.reject(error)
})

export function canUseDemo(error) {
  return explicitDemo
}
