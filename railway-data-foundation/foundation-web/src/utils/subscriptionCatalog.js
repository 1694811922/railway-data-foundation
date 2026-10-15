// These checks improve form selection. The backend remains the authority for ownership and access.
const real = row => row && !row._demo
export const subscriptionProviders = subjects => subjects.filter(row => real(row) && row.subject_type === 'PROVIDER' && row.status === 'ENABLED')
export const subscriptionDemands = (subjects, user) => subjects.filter(row => real(row) && row.subject_type === 'DEMAND' && row.status === 'ENABLED'
  && (user?.role === 'ADMIN' || user?.role === 'DEMAND' && row.subject_id === user.subject_id))

export function subscriptionResources(resources, providerId, role) {
  if (!providerId || !['ADMIN', 'DEMAND'].includes(role)) return []
  return resources.filter(row => real(row) && row.status === 'PUBLISHED'
    && (row.provider_subject_id === providerId || role === 'ADMIN' && row.provider_subject_id == null))
}

export function subscriptionCapabilities(capabilities, resourceId) {
  if (!resourceId) return []
  return capabilities.filter(row => real(row) && row.resource_id === resourceId && row.type === 'KAFKA_TOPIC'
    && row.auth_type === 'SASL' && ['DRAFT', 'VERIFIED', 'ENABLED', 'DISABLED'].includes(row.status)
    && /^[a-zA-Z0-9._-]{1,249}$/.test(row.topic || '') && !['.', '..'].includes(row.topic))
}

export function subscriptionSelectionError(form, { subjects, resources, capabilities }, user) {
  if (!['ADMIN', 'DEMAND'].includes(user?.role)) return '当前角色不能提交订阅申请'
  if (!subscriptionProviders(subjects).some(row => row.subject_id === form.provider_subject_id)) return '提供方不可用，请刷新登记选项'
  if (!subscriptionDemands(subjects, user).some(row => row.subject_id === form.demand_subject_id)) return '需求方与当前身份不匹配或已停用'
  if (!subscriptionResources(resources, form.provider_subject_id, user.role).some(row => row.resource_id === form.resource_id)) return '资源未发布或不属于所选提供方，请重新选择'
  if (!subscriptionCapabilities(capabilities, form.resource_id).some(row => row.capability_id === form.capability_id)) return '请选择该资源下使用 SASL 认证的精确 Kafka 主题能力'
  return ''
}

export function subscriptionEntry(query, references, user) {
  if (user?.role !== 'DEMAND') throw new Error('当前角色不能从资源目录提交订阅申请')
  if (typeof query.resource !== 'string' || !query.resource || typeof query.capability !== 'string' || !query.capability) throw new Error('申请入口缺少明确的资源或能力编号，请返回资源目录重新选择')
  const resource = references.resources.find(row => real(row) && row.resource_id === query.resource)
  if (!resource) throw new Error('申请资源不可用或不在当前可浏览目录，请返回资源目录重新选择')
  if (!resource.provider_subject_id) throw new Error('资源尚未明确提供方，请联系管理员')
  const selection = { provider_subject_id: resource?.provider_subject_id, demand_subject_id: user.subject_id, resource_id: query.resource, capability_id: query.capability }
  const error = subscriptionSelectionError(selection, references, user)
  if (error) throw new Error(error)
  return selection
}
