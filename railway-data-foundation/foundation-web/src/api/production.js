import { request } from '../utils/request'
const path=id=>`/production/capabilities/${encodeURIComponent(id)}/records`
export const productionApi={
  context:id=>request.get(`/capabilities/${encodeURIComponent(id)}/producer-context`,{quiet:true}),
  records:(id,params)=>request.get(path(id),{baseURL:'/provider/api/v1',params,quiet:true}),
  send:(id,recordId)=>request.post(`${path(id)}/${encodeURIComponent(recordId)}/send`,null,{baseURL:'/provider/api/v1',quiet:true}),
}
