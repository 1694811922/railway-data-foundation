import { request } from '../utils/request'
export const resourceApi = {
  list: () => request.get('/resources'),
  get: (id) => request.get(`/resources/${encodeURIComponent(id)}`),
  create: (body) => request.post('/resources', body),
  update: (id, body) => request.put(`/resources/${encodeURIComponent(id)}`, body),
  publish: (id) => request.post(`/resources/${encodeURIComponent(id)}/publish`),
  fields: (id) => request.get(`/resources/${encodeURIComponent(id)}/fields`),
  updateField: (id, name, body) => request.put(`/resources/${encodeURIComponent(id)}/fields/${encodeURIComponent(name)}`, body),
}
