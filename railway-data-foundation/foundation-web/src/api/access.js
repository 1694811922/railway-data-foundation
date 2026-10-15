import axios from 'axios'
import {request} from '../utils/request'
// Caller token remains in component memory; no defaults, storage or Foundation proxy for business rows.
const provider=axios.create({baseURL:import.meta.env.VITE_PROVIDER_BASE_URL||'/provider',timeout:12000})
export const credentialApi={list:params=>request.get('/caller-credentials',{params}),get:id=>request.get(`/caller-credentials/${encodeURIComponent(id)}`),
  issue:(id,body)=>request.post(`/subjects/${encodeURIComponent(id)}/credentials`,body),revoke:(id,body)=>request.post(`/caller-credentials/${encodeURIComponent(id)}/revoke`,body),events:id=>request.get(`/caller-credentials/${encodeURIComponent(id)}/events`)}
export const callProvider=(relationship,token,params)=>provider.get('/containers/events',{params,headers:{Authorization:`Bearer ${token}`,'X-Capability-Id':relationship.capability_id,'X-Relationship-Id':relationship.relationship_id}})
