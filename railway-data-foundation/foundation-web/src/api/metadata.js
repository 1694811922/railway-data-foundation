import { request } from '../utils/request'
export const metadataApi = {
  list: (params = {}) => request.get('/scan-tasks', { params }),
  start: (sourceId) => request.post(`/sources/${encodeURIComponent(sourceId)}/scan`),
  task: (id) => request.get(`/scan-tasks/${encodeURIComponent(id)}`),
  tables: (id) => request.get(`/scan-tasks/${encodeURIComponent(id)}/tables`),
  fields: (id, table) => request.get(`/scan-tasks/${encodeURIComponent(id)}/tables/${encodeURIComponent(table)}/fields`),
}
