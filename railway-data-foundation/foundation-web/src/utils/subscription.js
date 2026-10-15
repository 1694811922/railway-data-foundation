import {assertProviderReview,providerActionsFor,providerReady,providerReviewActions} from './providerReview.js'
export const subscriptionStatuses = { DRAFT:'草稿', PENDING:'待确认', APPROVED:'已确认', REVOKED:'已撤回申请' }
export const subscriptionActions = { submit:'提交确认', confirm:'确认整主题范围', return:'退回修改', revoke:'撤回申请',...providerReviewActions }
export const subscriptionTransitions = {
  submit:{DRAFT:'PENDING'}, confirm:{PENDING:'APPROVED'}, return:{PENDING:'DRAFT'},
  revoke:{DRAFT:'REVOKED',PENDING:'REVOKED',APPROVED:'REVOKED'},
  'provider-confirm':{PENDING:'PENDING'}, 'provider-return':{PENDING:'DRAFT'},
}
export const subscriptionActionsFor = row => Object.keys(subscriptionTransitions).filter(action => !action.startsWith('provider-')&&subscriptionTransitions[action][row.status]&&(action!=='confirm'||providerReady(row))).concat(providerActionsFor(row))
export const subscriptionExecutionLabels = {NOT_PROVISIONED:'未配置权限',PENDING:'待配置',ACTIVE:'已配置',REVOKE_PENDING:'待撤权',REVOKED:'已撤权',FAILED:'权限未确认'}
export const subscriptionExecutionActions = {provision:'配置订阅权限',stop:'停止订阅',retry:'重试执行',credential:'领取订阅凭据'}
export function subscriptionExecutionActionsFor(row) {
  const actions=[]
  if(row.execution_enabled&&row.status==='APPROVED'&&['NOT_PROVISIONED','REVOKED'].includes(row.execution_state))actions.push('provision')
  if(['PENDING','ACTIVE','FAILED','REVOKE_PENDING'].includes(row.execution_state))actions.push('stop')
  if(row.execution_state==='FAILED'||row.execution_state==='PENDING')actions.push('retry')
  if(row.execution_enabled&&row.broker_access_confirmed&&row.credential_available)actions.push('credential')
  return actions
}

export function assertSubscription(row, expected={}) {
  if (!row || row._demo || !/^KSG_[a-f0-9]{32}$/.test(row.grant_id || '') || !subscriptionStatuses[row.status]
      || row.scope!=='FULL_TOPIC' || row.auth_type!=='SASL' || !subscriptionExecutionLabels[row.execution_state]
      || typeof row.broker_access_confirmed!=='boolean' || !Number.isInteger(row.version) || row.version<1
      || row.consumer_group!==`rdf.subscription.${row.grant_id.slice(4)}`
      || !/^[a-zA-Z0-9._-]{1,249}$/.test(row.topic || '') || ['.','..'].includes(row.topic)
      || ['grant_name','provider_subject_id','demand_subject_id','resource_id','capability_id'].some(key=>typeof row[key]!=='string'||!row[key])
      || !Number.isFinite(Date.parse(row.valid_from)) || !Number.isFinite(Date.parse(row.valid_until)) || Date.parse(row.valid_from)>=Date.parse(row.valid_until)
      || ['password','credential','policy','allowed_fields'].some(key=>Object.hasOwn(row,key)))
    throw new Error('订阅审批响应不合法；审批不能表示Broker权限已生效')
  if(row.broker_access_confirmed&&(row.execution_state!=='ACTIVE'||row.status!=='APPROVED'))throw new Error('审批状态与实际权限不一致')
  if(row.execution_state!=='NOT_PROVISIONED'&&(!/^rdfk_[a-f0-9]{32}$/.test(row.broker_username||'')))throw new Error('缺少独立Kafka身份')
  if(row.credential_available&&(!row.broker_access_confirmed||row.execution_state!=='ACTIVE'))throw new Error('未确认权限不能领取凭据')
  if (['APPROVED'].includes(row.status) && !Number.isFinite(Date.parse(row.confirmed_at))) throw new Error('缺少整主题确认时间')
  assertProviderReview(row)
  for (const [key,value] of Object.entries(expected)) if (row[key]!==value) throw new Error('订阅审批响应与当前操作不一致，请刷新确认')
  return row
}
export function assertSubscriptionDelivery(data,current) {
  if(!data||data._demo||data.grant?.grant_id!==current.grant_id||data.grant?.version!==current.version+1
     ||data.sasl_mechanism!=='SCRAM-SHA-512'||!['SASL_SSL','SASL_PLAINTEXT'].includes(data.security_protocol)
     ||typeof data.password!=='string'||!/^ks_[A-Za-z0-9_-]{43}$/.test(data.password)
     ||!/^rkc_[A-Za-z0-9_-]{43}$/.test(data.consumption_token||'')
     ||data.username!==current.broker_username||data.topic!==current.topic||data.consumer_group!==current.consumer_group
     ||typeof data.bootstrap_servers!=='string'||!data.bootstrap_servers)throw new Error('凭据响应与当前授权不一致，请停止订阅后重新配置')
  assertSubscription(data.grant,{execution_state:'ACTIVE',broker_access_confirmed:true,credential_available:false})
  return data
}
export function assertSubscriptionPage(data,{limit=20,offset=0}={}) {
  if (!data || !Array.isArray(data.items) || !Number.isInteger(data.total) || data.total<0 || data.items.length>limit
      || (data.items.length>0 && data.total<offset+data.items.length)) throw new Error('订阅列表分页响应不合法')
  data.items.forEach(row=>assertSubscription(row))
  if(new Set(data.items.map(row=>row.grant_id)).size!==data.items.length) throw new Error('订阅列表出现重复记录')
  return data
}
export function assertSubscriptionAction(result,current,action) {
  const status=subscriptionTransitions[action]?.[current.status]
  if (!status||!subscriptionActionsFor(current).includes(action)) throw new Error('当前审批状态不允许该操作')
  const row=assertSubscription(result,{grant_id:current.grant_id,status,version:current.version+1,
    provider_subject_id:current.provider_subject_id,demand_subject_id:current.demand_subject_id,
    resource_id:current.resource_id,capability_id:current.capability_id,topic:current.topic,consumer_group:current.consumer_group})
  if(row.provider_review_mode!==current.provider_review_mode||action==='provider-confirm'&&!row.provider_confirmed_at||['submit','return','provider-return'].includes(action)&&row.provider_confirmed_at!=null)throw new Error('提供方确认响应与当前操作不一致')
  return row
}
export function subscriptionPayload(form,editing=false) {
  const text=key=>String(form[key]||'').trim()
  if(!text('grant_name')||text('grant_name').length>120||!text('purpose')||text('purpose').length>1000||!text('reason')||text('reason').length>1000) throw new Error('请填写授权名称、用途和操作原因，并检查长度')
  if(!Array.isArray(form.valid_range)||form.valid_range.length!==2||form.valid_range.some(value=>value==null||value===''))throw new Error('请选择完整的有效期')
  const [from,until]=form.valid_range.map(value=>new Date(value).getTime())
  if(!Number.isFinite(from)||!Number.isFinite(until)||from>=until||until<=Date.now()) throw new Error('请选择尚未到期且起止顺序正确的有效期')
  const body={grant_name:text('grant_name'),purpose:text('purpose'),reason:text('reason'),valid_from:new Date(from).toISOString(),valid_until:new Date(until).toISOString()}
  if(editing){if(!Number.isInteger(form.version)||form.version<1)throw new Error('缺少读取时版本，请刷新');body.version=form.version}
  else {
    if(!['provider_subject_id','demand_subject_id','resource_id','capability_id'].every(key=>text(key)))throw new Error('请选择双方主体、数据资源和Kafka能力')
    if(form.provider_subject_id===form.demand_subject_id)throw new Error('提供方与需求方必须不同')
    if(form.whole_topic_acknowledged!==true)throw new Error('请明确接受完整消息及历史的整主题范围')
    for(const key of ['provider_subject_id','demand_subject_id','resource_id','capability_id'])body[key]=text(key)
    body.whole_topic_acknowledged=true
  }
  return body
}

// Each independent read area owns a gate. Closing, changing selection or retrying invalidates old responses.
export function subscriptionReadGate() {
  let epoch=0,active=true
  return {begin:()=>++epoch,current:token=>active&&token===epoch,invalidate:()=>++epoch,dispose:()=>{active=false;++epoch}}
}
