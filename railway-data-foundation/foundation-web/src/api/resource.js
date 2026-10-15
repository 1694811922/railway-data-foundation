import { request } from '../utils/request'
export const resourceApi = {
  list: (params = {}) => request.get('/resources', { params }),
  get: (id) => request.get(`/resources/${encodeURIComponent(id)}`),
  versions: (id,params={}) => request.get(`/resources/${encodeURIComponent(id)}/versions`,{params}),
  version: (id,version) => request.get(`/resources/${encodeURIComponent(id)}/versions/${version}`),
  comparisonScans: (id,params={}) => request.get(`/resources/${encodeURIComponent(id)}/schema-comparison/scans`,{params}),
  compareSchema: (id,scanTaskId) => request.get(`/resources/${encodeURIComponent(id)}/schema-comparison`,{params:{scanTaskId}}),
  create: (body) => request.post('/resources', body),
  update: (id, body) => request.put(`/resources/${encodeURIComponent(id)}`, body),
  publish: (id) => request.post(`/resources/${encodeURIComponent(id)}/publish`),
  fields: (id) => request.get(`/resources/${encodeURIComponent(id)}/fields`),
  updateField: (id, name, body) => request.put(`/resources/${encodeURIComponent(id)}/fields/${encodeURIComponent(name)}`, body),
  delete: (id) => request.delete(`/resources/${encodeURIComponent(id)}`),
}
