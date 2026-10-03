export const resourcePayload = form => Object.fromEntries([
  'resource_name','source_id','schema_name','physical_table','resource_type',
  'business_description','owner_department','security_level','update_mode',
].map(key => [key, form[key]]))

export function validateResource(form) {
  const fields = [['source_id','来源系统'],['schema_name','数据库模式'],['physical_table','物理表'],
    ['resource_name','资源名称'],['business_description','业务描述'],['owner_department','责任单位']]
  for (const [key, label] of fields) if (!String(form[key] || '').trim()) return `请填写${label}`
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
