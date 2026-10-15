import { reactive } from 'vue'
import { clearReceivingDraft } from './receiving.js'
export const explicitDemo = import.meta.env?.VITE_USE_MOCK === 'true'
export const session = reactive({ user: null, csrf: null, epoch: 0, initialized: false })
export function clearSession() { clearReceivingDraft();session.user = null; session.csrf = null; session.epoch++; session.initialized = true }
export function acceptUser(user) {
  if (!user || !['ADMIN','PROVIDER','DEMAND'].includes(user.role) || !user.user_id || !user.username || (user.role !== 'ADMIN' && !user.subject_id)) throw new Error('登录身份不完整')
  clearReceivingDraft();session.user = user; session.epoch++; session.initialized = true
}
