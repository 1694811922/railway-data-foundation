// Technical validation mirrors V0.2 CapabilityRequest/CapabilityService; no mock records here.
export const capabilitySupport = {
  REST_API: '登记已有接口。当前可验证并启停 GET /containers/events，无认证；保存不会自动生成业务接口。',
  KAFKA_TOPIC: '登记已有消息主题。当前可验证无认证主题的元数据；消息消费启停尚未接入。',
  CDC_STREAM: '登记来源表的变化流配置；当前尚未连接变更捕获组件，仅保存能力信息。',
  READONLY_QUERY: '登记受控查询模板的编号；当前尚未执行模板，请勿填写 SQL。',
}

export function validateCapability(form) {
  if (!form.resource_id || !form.capability_name.trim()) return '请填写能力名称并选择资源'
  if (form.type === 'REST_API') {
    if (!/^\/(?!\/)[A-Za-z0-9/_{}.-]{1,499}$/.test(form.endpoint.trim()))
      return '接口路径须以 / 开头，例如 /containers/events；不要填写完整网址或查询参数'
    if (!['NONE', 'TOKEN', 'BASIC'].includes(form.auth_type)) return '请选择 REST 接口支持的认证方式'
  }
  if (['KAFKA_TOPIC', 'CDC_STREAM'].includes(form.type) && !/^[A-Za-z0-9][A-Za-z0-9._-]{0,248}$/.test(form.topic.trim()))
    return '请填写有效主题名称，仅使用字母、数字、点、下划线或短横线'
  if (form.type === 'KAFKA_TOPIC' && !['NONE', 'SASL'].includes(form.auth_type)) return '请选择消息主题支持的认证方式'
  if (form.type === 'CDC_STREAM' && !form.source_table.trim()) return '请先选择资源，来源表将自动带出'
  if (form.type === 'READONLY_QUERY' && !/^[A-Za-z][A-Za-z0-9_]{0,119}$/.test(form.query_template_code.trim()))
    return '查询模板代码须以字母开头，仅使用字母、数字和下划线，不接受 SQL'
  return ''
}

const quotePowerShell = value => "'" + String(value).replaceAll("'", "''") + "'"
export function invocationCommand(guide) {
  if (!guide) return ''
  return `Invoke-RestMethod -Method ${guide.http_method} -Uri ${quotePowerShell(guide.request_url + '?limit=5')} -Headers @{ ${quotePowerShell(guide.header_name)} = ${quotePowerShell(guide.header_value)} }`
}
