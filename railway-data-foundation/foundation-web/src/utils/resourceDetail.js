export function filterResourceFields(rows, filters={}) {
  const terms=(filters.keyword || '').trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row=>{
    if(filters.mark==='primary' && !row.primary_key) return false
    if(filters.mark==='time' && !row.time_field) return false
    if(filters.sensitivity_level && row.sensitivity_level!==filters.sensitivity_level) return false
    const text=['field_name','business_name','data_type','business_description','technical_comment']
      .map(key=>String(row[key] || '')).join(' ').toLocaleLowerCase()
    return terms.every(term=>text.includes(term))
  })
}

// Reject malformed/foreign rows instead of rendering unrelated metadata in a detail tab.
export function detailRows(result,id,key,demo) {
  if(result.demo!==demo || !Array.isArray(result.rows)) throw new Error('详情数据来源或结构不一致')
  const seen=new Set()
  for(const row of result.rows) {
    if(!row || typeof row[key]!=='string' || !row[key] || seen.has(row[key])
      || (row.resource_id!==undefined && row.resource_id!==id)) throw new Error('详情数据不属于当前资源或编号重复')
    seen.add(row[key])
  }
  return result.rows
}

export function fieldChanges(form, resourceVersion) {
  return {business_name:form.business_name,business_description:form.business_description,
    time_field:form.time_field,sensitivity_level:form.sensitivity_level,resource_version:resourceVersion}
}
export function validateFieldChanges(form) {
  if(typeof form.business_name!=='string' || form.business_name.length>120) return '业务名称不能超过120字'
  if(typeof form.business_description!=='string' || form.business_description.length>1000) return '业务描述不能超过1000字'
  if(typeof form.time_field!=='boolean') return '请确认时间字段标记'
  if(!['公开','内部','敏感','机密'].includes(form.sensitivity_level)) return '请选择敏感等级'
  return ''
}
export function fieldEditScope(scope,item,rows,demo) {
  if(item?.status==='RETIRED')return '已退役资源只读，不能编辑字段'
  if(!scope || !item || scope.resource_id!==item.resource_id || scope.version!==item.version || scope.demo!==demo)
    return '资源信息已变化，请刷新后重新打开字段编辑'
  if(!rows.some(row=>row.field_name===scope.field_name)) return '当前字段尚未读取成功，请重新加载字段'
  const row=rows.find(row=>row.field_name===scope.field_name)
  if(!Number.isInteger(scope.resource_version) || scope.resource_version<1 || scope.resource_version!==row.resource_version)
    return '字段版本尚未确认，请刷新后重新编辑'
  return ''
}
