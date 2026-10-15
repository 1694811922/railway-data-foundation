import { request } from '../utils/request'
export const sharingApi={
  list:params=>request.get('/relationships',{params}),
  get:id=>request.get(`/relationships/${encodeURIComponent(id)}`),
  create:body=>request.post('/relationships',body),
  update:(id,body)=>request.put(`/relationships/${encodeURIComponent(id)}`,body),
  action:(id,action,body)=>request.post(`/relationships/${encodeURIComponent(id)}/${action}`,body),
  events:id=>request.get(`/relationships/${encodeURIComponent(id)}/events`),
  policies:params=>request.get('/access-policies',{params}),
}
