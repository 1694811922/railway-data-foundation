import { request } from '../utils/request'
import { explicitDemo } from '../utils/sessionState'

function realOnly(action) {
  if (explicitDemo) return Promise.reject(new Error('演示模式不操作真实账号'))
  return action()
}
export const accountApi = {
  catalog: params => realOnly(() => request.get('/accounts/catalog', { params, quiet: true })),
  subjects: params => realOnly(() => request.get('/accounts/subject-options', { params, quiet: true })),
  create: body => realOnly(() => request.post('/accounts', body, { quiet: true })),
  status: (id, body) => realOnly(() => request.post(`/accounts/${encodeURIComponent(id)}/status`, body, { quiet: true })),
  password: (id, body) => realOnly(() => request.post(`/accounts/${encodeURIComponent(id)}/password`, body, { quiet: true })),
}
