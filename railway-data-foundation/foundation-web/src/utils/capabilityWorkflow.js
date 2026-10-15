export const supportedRest = row => row?.type==='REST_API' && row.http_method==='GET' && row.auth_type==='NONE' && row.endpoint==='/containers/events'

export const supportedKafka = row => row?.type==='KAFKA_TOPIC' && row.protocol==='KAFKA' && ['NONE','SASL'].includes(row.auth_type)
export const kafkaRuntimeMatches = (row,state) => supportedKafka(row) && !!state
  && state.capability_id===row.capability_id && state.type===row.type && state.topic===row.topic && state.auth_type===row.auth_type
  && typeof state.enabled==='boolean' && typeof state.reason==='string' && state.reason.length>0

// Runtime admission is separate from subscriber authorization.
export function capabilityActionReason(row,action,demo=false) {
  if(!row) return '请刷新后重新选择能力'
  if(action==='verify') {
    if(!['DRAFT','VERIFIED','DISABLED'].includes(row.status)) return '仅草稿、已验证或停用能力可验证'
    if(!demo && !supportedRest(row) && !supportedKafka(row))
      return '当前只支持指定REST适配器与Kafka主题验证'
  } else if(action==='enable') {
    if(!['VERIFIED','DISABLED'].includes(row.status)) return '请先验证能力，或选择已停用能力'
    if(row.health_status!=='UP') return '最近验证未通过，请恢复服务后重新验证'
    if(!demo && !supportedRest(row) && !supportedKafka(row)) return '该类型尚未实现真实运行时启用'
  } else if(action==='disable') {
    if(row.status!=='ENABLED') return '只有已启用能力可停用'
    if(!demo && !supportedRest(row) && !supportedKafka(row)) return '该类型尚未实现真实运行时停用'
  } else return '不支持此操作'
  return ''
}
export function capabilityRowScope(row,rows,demo) {
  const latest=rows.find(item=>item.capability_id===row?.capability_id)
  if(!latest || latest.version!==row.version || latest.status!==row.status || latest.resource_id!==row.resource_id || latest.type!==row.type)
    return '能力信息已变化，请刷新后重新选择'
  if((latest._demo===true)!==demo) return '能力与当前页面的数据来源不一致，请重新选择'
  return ''
}
export function capabilityEditScope(scope,form,rows,demo) {
  if(!scope) return '' // New registrations are checked by capabilitySaveScope.
  const invalid=capabilityRowScope(scope,rows,demo)
  if(invalid) return invalid
  if(scope.status!=='DRAFT') return '只有草稿能力可编辑'
  if(form.resource_id!==scope.resource_id || form.type!==scope.type) return '编辑时不能修改所属资源或能力类型'
  return ''
}
export function capabilityStateExplanation(row,demo=false) {
  if(supportedKafka(row)) {
    const text={DRAFT:'消息能力尚未验证。',VERIFIED:'主题元数据最近验证通过，仍需显式启用。',ENABLED:'允许提供服务发送新消息；订阅授权尚未接入。',DISABLED:'拒绝新的发送请求；不会自动启用，不撤销订阅权限或删除已有消息。',RETIRED:'没有提供退役或恢复操作。'}[row.status] || '状态未识别'
    return demo?'演示状态：'+text+' 不开放真实服务。':text
  }
  const text={DRAFT:'草稿只登记访问配置，可以编辑并验证；尚不能通过该能力查询业务数据。',
    VERIFIED:'验证通过只说明最近探测成功；启用后才开放当前支持的REST查询。',
    ENABLED:'指定REST查询允许新请求，提供服务直接读取源库；仍会检查来源和资源状态。',
    DISABLED:'已停止新的REST查询；再次验证不会自动启用，需显式重新启用。',
    RETIRED:'当前为已退役状态；本原型没有提供退役或恢复操作。'}[row?.status] || '状态未识别，请刷新检查实际记录。'
  return demo ? '演示状态：'+text+' 操作只修改浏览器中的演示元数据，不开放真实服务。' : text
}
export function capabilityPayload(form) {
  return Object.fromEntries(['capability_name','resource_id','type','http_method','endpoint','topic','source_table','protocol','auth_type','message_schema','query_template_code','description']
    .map(key=>[key,form[key]]))
}
