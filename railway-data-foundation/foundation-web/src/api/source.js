import { request } from '../utils/request'
export const sourceApi = {
  list: (params = {}) => request.get('/sources', { params: { limit: 200, ...params } }),
  get: (id) => request.get(`/sources/${encodeURIComponent(id)}`),
  create: (body) => request.post('/sources', body),
  update: (id, body) => request.put(`/sources/${encodeURIComponent(id)}`, body),
  test: (id) => request.post(`/sources/${encodeURIComponent(id)}/test`),
  status: (id, status) => request.patch(`/sources/${encodeURIComponent(id)}/status`, { status }),
  delete: (id) => request.delete(`/sources/${encodeURIComponent(id)}`),
}
