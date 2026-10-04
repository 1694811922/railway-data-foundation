import { request } from '../utils/request'
export const capabilityApi = {
  list: (params = {}) => request.get('/capabilities', { params }),
  byResource: (id) => request.get(`/resources/${encodeURIComponent(id)}/capabilities`),
  get: (id) => request.get(`/capabilities/${encodeURIComponent(id)}`),
  runtime: (id) => request.get(`/capabilities/${encodeURIComponent(id)}/runtime`),
  invocation: (id) => request.get(`/capabilities/${encodeURIComponent(id)}/invocation`),
  create: (body) => request.post('/capabilities', body),
  update: (id, body) => request.put(`/capabilities/${encodeURIComponent(id)}`, body),
  verify: (id) => request.post(`/capabilities/${encodeURIComponent(id)}/verify`),
  enable: (id) => request.post(`/capabilities/${encodeURIComponent(id)}/enable`),
  disable: (id) => request.post(`/capabilities/${encodeURIComponent(id)}/disable`),
}
