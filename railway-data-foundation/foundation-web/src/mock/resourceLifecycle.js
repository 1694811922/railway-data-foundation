import {demoStore,demoResourceConflict} from './demoData'
import {lifecycleBody} from '../utils/resourceLifecycle'
export function demoLifecycleImpact(id){
 const r=demoStore.list('resources').find(x=>x.resource_id===id);if(!r)throw new Error('演示资源不存在')
 const capabilities=demoStore.list('capabilities').filter(x=>x.resource_id===id).length
 return {resource_id:id,resource_version:r.version,status:r.status,capabilities,active_rest_relationships:0,kafka_executions:0,kafka_revoke_pending:0,can_delete:r.status==='DRAFT'&&!capabilities}
}
export function demoLifecycleChange(id,action,body){
 const r=demoStore.list('resources').find(x=>x.resource_id===id);if(body.version!==r?.version)throw demoResourceConflict()
 lifecycleBody(r,action,body.reason)
 const resource=demoStore.put('resources',{...r,status:{publish:'PUBLISHED',unpublish:'OFFLINE',republish:'PUBLISHED',retire:'RETIRED'}[action],version:r.version+1,updated_at:new Date().toISOString()},'resource_id',{action:action.toUpperCase(),reason:body.reason})
 return {resource,impact:demoLifecycleImpact(id)}
}
