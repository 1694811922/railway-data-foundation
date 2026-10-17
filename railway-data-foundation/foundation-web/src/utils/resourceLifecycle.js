export const resourceStates={DRAFT:'草稿',PUBLISHED:'已发布',OFFLINE:'已下架',RETIRED:'已退役'}
export const lifecycleLabels={publish:'发布资源',unpublish:'下架资源',republish:'重新发布',retire:'退役资源'}
export function lifecycleActions(status){return ({DRAFT:['publish'],PUBLISHED:['unpublish'],OFFLINE:['republish','retire'],RETIRED:[]})[status]||[]}
export function lifecycleBody(resource,action,reason){
 if(!resource||!Number.isSafeInteger(resource.version)||resource.version<1||!lifecycleActions(resource.status).includes(action))throw new Error('资源状态已变化，请刷新后重新选择操作')
 if(typeof reason!=='string'||!reason.trim()||reason.length>500)throw new Error('请填写操作原因，最多500字')
 return {version:resource.version,reason:reason.trim()}
}
export function lifecycleImpact(response,resource,demo){
 const value=response?.value
 if(response?.demo!==demo||!value||value.resource_id!==resource.resource_id||value.resource_version!==resource.version||value.status!==resource.status||typeof value.can_delete!=='boolean')throw new Error('资源状态或影响范围已变化，请刷新后重新确认')
 for(const key of ['capabilities','active_rest_relationships','kafka_executions','kafka_revoke_pending'])if(!Number.isSafeInteger(value[key])||value[key]<0)throw new Error('关联影响响应不完整，请重试')
 return value
}
