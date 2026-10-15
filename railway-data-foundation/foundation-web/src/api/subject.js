import { request } from '../utils/request'
export const subjectApi = {
  list: (params={}) => request.get('/subjects',{params:{limit:200,...params}}),
  get: id => request.get(`/subjects/${encodeURIComponent(id)}`),
  create: body => request.post('/subjects',body),
  update: (id,body) => request.put(`/subjects/${encodeURIComponent(id)}`,body),
  status: (id,body) => request.post(`/subjects/${encodeURIComponent(id)}/status`,body),
  delete: id => request.delete(`/subjects/${encodeURIComponent(id)}`),
}
