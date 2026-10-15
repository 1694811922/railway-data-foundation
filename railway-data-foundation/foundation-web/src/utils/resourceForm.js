export const resourcePayload = (form, version) => ({...Object.fromEntries([
  'resource_name','source_id','scan_task_id','schema_name','physical_table','resource_type',
  'business_description','owner_department','security_level','update_mode',
].map(key => [key, form[key]])), ...(version===undefined ? {} : {version})})

export const isResourceConflict = error => error?.response?.status===409 && error.response.data?.code===10009

export function validateResource(form) {
  const fields = [['source_id','来源系统'],['schema_name','数据库模式'],['physical_table','物理表'],
    ['resource_name','资源名称'],['business_description','业务描述'],['owner_department','责任单位']]
  for (const [key, label] of fields) if (!String(form[key] || '').trim()) return `请填写${label}`
  for(const [key,max,label] of [['resource_name',120,'资源名称'],['schema_name',64,'数据库模式'],['physical_table',64,'物理表'],['business_description',2000,'业务描述'],['owner_department',120,'责任单位']])
    if(typeof form[key]!=='string' || form[key].length>max)return `${label}不能超过${max}字`
  if(form.resource_type!=='TABLE')return '当前仅支持数据库表资源'
  if (!['公开','内部','敏感','机密'].includes(form.security_level)) return '请选择安全等级'
  if (!['实时','每日','按需'].includes(form.update_mode)) return '请选择更新方式'
  return ''
}

export function resourceError(error, operation) {
  if (error.response?.data?.message) return error.response.data.message
  if (error.code === 'ECONNABORTED') return `${operation}请求超时，结果尚未确认，请刷新检查实际状态`
  if (!error.response) return `${operation}请求失败，请检查后端服务并刷新确认实际状态`
  return error.message || `${operation}失败`
}
