export const receivingStates = { STOPPED: '已停止', STOPPING: '正在停止', STARTING: '正在启动', RUNNING: '接收中', RETRYING: '等待恢复', BLOCKED: '已阻塞' }
export const receivingColors = { RUNNING: 'success', BLOCKED: 'danger', RETRYING: 'warning', STOPPING: 'warning' }
export function receivingPage(value) {
  if (!value || !Array.isArray(value.items) || !Number.isSafeInteger(value.total) || value.total < value.items.length) throw new Error('接收服务列表响应无效')
  return value
}
export function receivingPayload(form) {
  if (!form.task_name?.trim() || form.task_name.trim().length > 120 || !/^KSG_[a-f0-9]{32}$/.test(form.grant_id) || !/^ks_[A-Za-z0-9_-]{43}$/.test(form.password) || !/^rkc_[A-Za-z0-9_-]{43}$/.test(form.consumption_token) || !['EARLIEST', 'LATEST'].includes(form.start_position)) throw new Error('请完整填写任务、Kafka密码和技术令牌')
  return { task_name: form.task_name.trim(), grant_id: form.grant_id, password: form.password, consumption_token: form.consumption_token, start_position: form.start_position }
}
export function readablePayload(value) {
  if (!value || value.encoding === 'NULL') return '空值消息'
  if (value.encoding === 'BASE64') return `二进制内容（Base64）\n${value.content}`
  if (value.encoding !== 'UTF8') throw new Error('消息编码无效')
  try { return JSON.stringify(JSON.parse(value.content), null, 2) } catch { return value.content }
}
let draft = null
let draftTimer
export function offerReceivingDraft(delivery, epoch) {
  clearReceivingDraft()
  draft = { grant_id: delivery.grant.grant_id, password: delivery.password, consumption_token: delivery.consumption_token, epoch, expires: Date.now() + 60000 }
  draftTimer=setTimeout(clearReceivingDraft,60000);draftTimer.unref?.()
}
export function takeReceivingDraft(grant, epoch) {
  const current = draft; clearReceivingDraft()
  return current?.grant_id === grant && current.epoch === epoch && current.expires > Date.now() ? current : null
}
export function clearReceivingDraft() { draft = null; clearTimeout(draftTimer);draftTimer=undefined }
export function receivingReadGate() {
  let revision = 0, disposed = false
  return { next: () => ++revision, current: value => !disposed && value === revision, invalidate: () => ++revision, dispose: () => { disposed = true; ++revision } }
}
