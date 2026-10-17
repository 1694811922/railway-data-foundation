import { request } from '../utils/request'
// Routes directly to the demand service. Foundation never forwards these payloads.
const baseURL = '/demand/api/v1'
export const receivingApi = {
  logs: params => request.get('/receiving-tasks/transfer-logs', { baseURL, params, quiet: true }),
  list: params => request.get('/receiving-tasks', { baseURL, params, quiet: true }),
  get: id => request.get(`/receiving-tasks/${encodeURIComponent(id)}`, { baseURL, quiet: true }),
  progress: id => request.get(`/receiving-tasks/${encodeURIComponent(id)}/progress`, { baseURL, quiet: true }),
  download: (id,message,format,signal) => request.get(`/receiving-tasks/${encodeURIComponent(id)}/messages/${message}/download`, { baseURL,params:{format},responseType:'blob',download:true,quiet:true,timeout:45000,signal }),
  export: (id,params,signal) => request.get(`/receiving-tasks/${encodeURIComponent(id)}/messages/export`, { baseURL,params,responseType:'blob',download:true,quiet:true,timeout:45000,signal }),
  create: body => request.post('/receiving-tasks', body, { baseURL }),
  credentials: (id,version,configuration) => request.put(`/receiving-tasks/${encodeURIComponent(id)}/credentials`, {version,configuration}, {baseURL}),
  action: (id, action, version) => request.post(`/receiving-tasks/${encodeURIComponent(id)}/${action}`, { version }, { baseURL }),
  messages: (id, params) => request.get(`/receiving-tasks/${encodeURIComponent(id)}/messages`, { baseURL, params, quiet: true }),
  message: (id, message) => request.get(`/receiving-tasks/${encodeURIComponent(id)}/messages/${message}`, { baseURL, quiet: true }),
}
