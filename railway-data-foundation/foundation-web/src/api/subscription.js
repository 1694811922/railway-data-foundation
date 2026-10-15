import { request } from '../utils/request'

// This workflow is real-only. A failed request must never become mock approval.
export const subscriptionApi = {
  list: params => request.get('/kafka-subscriptions', { params }),
  get: id => request.get(`/kafka-subscriptions/${encodeURIComponent(id)}`),
  create: body => request.post('/kafka-subscriptions', body),
  update: (id, body) => request.put(`/kafka-subscriptions/${encodeURIComponent(id)}`, body),
  action: (id, action, body) => request.post(`/kafka-subscriptions/${encodeURIComponent(id)}/${action}`, body),
  events: id => request.get(`/kafka-subscriptions/${encodeURIComponent(id)}/events`),
  execute:(id,action,body)=>request.post(`/kafka-subscriptions/${encodeURIComponent(id)}/${action}`,body),
  consumption:(id,params)=>request.get(`/kafka-subscriptions/${encodeURIComponent(id)}/consumption`,{params,quiet:true}),
  claim:(id,body)=>request.post(`/kafka-subscriptions/${encodeURIComponent(id)}/credential`,body),
}
