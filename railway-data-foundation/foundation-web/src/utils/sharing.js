import {assertProviderReview,providerActionsFor,providerReady,providerReviewActions} from './providerReview.js'
export const relationshipStatuses={DRAFT:'草稿',PENDING:'待确认',CONFIRMED:'已确认',ACTIVE:'登记已启用',SUSPENDED:'已暂停',REVOKED:'已撤回'}
export const relationshipActions={submit:'提交确认',confirm:'确认通过',return:'退回修改',enable:'启用登记',suspend:'暂停',revoke:'撤回',...providerReviewActions}
const transitions={DRAFT:['submit','revoke'],PENDING:['confirm','return','revoke'],CONFIRMED:['enable','revoke'],ACTIVE:['suspend','revoke'],SUSPENDED:['enable','revoke'],REVOKED:[]}
export const actionsFor=row=>[...(transitions[row?.status]||[]).filter(action=>action!=='confirm'||providerReady(row)),...providerActionsFor(row)]
export const actionTarget={submit:'PENDING',confirm:'CONFIRMED',return:'DRAFT',enable:'ACTIVE',suspend:'SUSPENDED',revoke:'REVOKED','provider-confirm':'PENDING','provider-return':'DRAFT'}
export const timeFields=rows=>rows.filter(row=>row.time_field===true && /^(datetime|timestamp|date)(\(.*\))?$/i.test(row.data_type))
export const restCapabilities=(rows,resourceId)=>rows.filter(row=>row.resource_id===resourceId&&row.type==='REST_API'&&row.status!=='RETIRED')
export function iso(value) {
  if(!value)throw new Error('请选择完整时间范围')
  const date=new Date(value)
  if(Number.isNaN(date.getTime()))throw new Error('时间格式不正确')
  return date.toISOString()
}
function range(values,label){
  if(!Array.isArray(values)||values.length!==2)throw new Error(`请选择${label}`)
  const pair=values.map(iso)
  if(pair[0]>=pair[1])throw new Error(`${label}结束时间必须晚于开始时间`)
  return pair
}
export function sharingPayload(form,{editing=false,fields=[],now=Date.now()}={}) {
  const text=key=>String(form[key]??'').trim(),name=text('relationship_name'),purpose=text('purpose'),reason=text('reason')
  if(!name||name.length>120)throw new Error('关系名称必填，最多120字')
  if(!purpose||purpose.length>1000)throw new Error('使用目的必填，最多1000字')
  if(!reason||reason.length>1000)throw new Error('操作原因必填，最多1000字')
  const [valid_from,valid_until]=range(form.valid_range,'授权有效期'),[data_from,data_until]=range(form.data_range,'数据时间范围')
  if(new Date(valid_until).getTime()<=now)throw new Error('授权有效期已经结束')
  const allowed_fields=Array.isArray(form.allowed_fields)?[...form.allowed_fields]:[]
  const names=new Set(fields.map(row=>row.field_name))
  if(!allowed_fields.length||allowed_fields.length>200||new Set(allowed_fields).size!==allowed_fields.length||allowed_fields.some(name=>name==='*'||!names.has(name)))throw new Error('请选择当前资源中的允许字段，不能留空、使用通配符或重复')
  const time_field=text('time_field')
  if(!timeFields(fields).some(row=>row.field_name===time_field))throw new Error('请选择已人工标记的日期或时间字段')
  const body={relationship_name:name,purpose,reason,valid_from,valid_until,policy:{allowed_fields,time_field,data_from,data_until}}
  if(editing){if(!Number.isInteger(form.version)||form.version<1)throw new Error('缺少有效版本，请刷新');return {...body,version:form.version}}
  for(const key of ['provider_subject_id','demand_subject_id','resource_id','capability_id'])if(!text(key))throw new Error('请选择双方主体、资源和接口能力')
  if(form.provider_subject_id===form.demand_subject_id)throw new Error('提供方与需求方不能相同')
  return {...body,provider_subject_id:text('provider_subject_id'),demand_subject_id:text('demand_subject_id'),resource_id:text('resource_id'),capability_id:text('capability_id')}
}
export function assertPolicy(row,resourceId) {
  if(!row||row._demo||!/^POL_[a-f0-9]{32}$/.test(row.policy_id)||!row.resource_id||(resourceId&&row.resource_id!==resourceId)
    ||!Array.isArray(row.allowed_fields)||!row.allowed_fields.length||new Set(row.allowed_fields).size!==row.allowed_fields.length
    ||row.allowed_fields.some(f=>typeof f!=='string'||!f||f==='*')||!row.time_field||!Number.isInteger(row.version)||row.version<1)
    throw new Error('访问策略响应不完整或归属不正确')
  range([row.data_from,row.data_until],'数据时间范围');return row
}
export function assertRelationship(row,expected={}) {
  if(!row||row._demo||!/^REL_[a-f0-9]{32}$/.test(row.relationship_id)||!row.relationship_name||!row.provider_subject_id||!row.demand_subject_id
    ||row.provider_subject_id===row.demand_subject_id||!row.resource_id||!row.capability_id||!Object.hasOwn(relationshipStatuses,row.status)||!Number.isInteger(row.version)||row.version<1)
    throw new Error('共享关系响应格式不正确')
  assertPolicy(row.policy,row.resource_id);range([row.valid_from,row.valid_until],'授权有效期')
  assertProviderReview(row)
  if(['CONFIRMED','ACTIVE','SUSPENDED'].includes(row.status)&&!row.confirmed_at)throw new Error('共享关系缺少确认记录')
  for(const key of ['relationship_id','provider_subject_id','demand_subject_id','resource_id','capability_id','version','status'])
    if(expected[key]!==undefined&&expected[key]!==row[key])throw new Error('关系响应与当前操作不匹配，请刷新检查结果')
  return row
}
export function assertActionResult(row,current,action) {
  if(!actionsFor(current).includes(action))throw new Error('当前关系不允许此操作')
  assertRelationship(row,{relationship_id:current.relationship_id,provider_subject_id:current.provider_subject_id,demand_subject_id:current.demand_subject_id,
    resource_id:current.resource_id,capability_id:current.capability_id,version:current.version+1,status:actionTarget[action]})
  if(row.policy.policy_id!==current.policy.policy_id||row.policy.version!==current.policy.version
    ||JSON.stringify(row.policy.allowed_fields)!==JSON.stringify(current.policy.allowed_fields)
    ||['time_field','data_from','data_until'].some(key=>row.policy[key]!==current.policy[key]))throw new Error('状态操作返回了不同策略，请刷新检查')
  if(row.provider_review_mode!==current.provider_review_mode||action==='provider-confirm'&&!row.provider_confirmed_at||['submit','return','provider-return'].includes(action)&&row.provider_confirmed_at!=null)throw new Error('提供方确认响应与当前操作不一致')
  return row
}
export function validity(row,now=Date.now()) {
  if(new Date(row.valid_until).getTime()<=now)return '已到期'
  if(new Date(row.valid_from).getTime()>now)return '尚未开始'
  return '有效期内'
}
export function filterRelationships(rows,{keyword='',status=''}={}) {
  const q=keyword.trim().toLowerCase()
  return rows.filter(row=>(!status||row.status===status)&&(!q||['relationship_id','relationship_name','provider_name','demand_name','resource_name','capability_name'].some(key=>String(row[key]||'').toLowerCase().includes(q))))
}
