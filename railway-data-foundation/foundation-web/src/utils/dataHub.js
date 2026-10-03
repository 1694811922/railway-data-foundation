import { sourceApi } from '../api/source'
import { metadataApi } from '../api/metadata'
import { resourceApi } from '../api/resource'
import { capabilityApi } from '../api/capability'
import { canUseDemo } from './request'
import { demoStore, nextDemoId, demoInvocation, isDemoResource, isDemoSource } from '../mock/demoData'

const forceMock = import.meta.env.VITE_USE_MOCK === 'true'
const missing = (error) => forceMock || canUseDemo(error)
async function read(apiCall, name, filter = () => true) {
  if (!forceMock) {
    try { return { rows: await apiCall(), demo: false } }
    catch (error) { if (!missing(error)) throw error }
  }
  return { rows: demoStore.list(name).filter(filter), demo: true }
}
// Real operations never become simulated successes after a lost response.
async function sourceWrite(apiCall, fallback, demoMode=false) {
  if (forceMock || demoMode) return { value:fallback(),demo:true }
  return { value:await apiCall(),demo:false }
}
async function resourceRead(id, apiCall, fallback, rows=false) {
  const result = await sourceWrite(apiCall, () => {
    if (!isDemoResource(id)) throw new Error('演示资源不存在，请返回资源目录重新选择')
    return fallback()
  }, isDemoResource(id))
  return rows ? { rows:result.value,demo:result.demo } : result
}
function taskRead(id, apiCall, fallback) {
  const demoTask = /^SCAN_DEMO_[A-Z0-9]+$/.test(id || '') && demoStore.list('tasks').some(t => t.task_id === id && t._demo === true)
  return sourceWrite(apiCall, () => {
    if (!demoTask) throw new Error('演示扫描任务不存在，请重新选择任务')
    return fallback()
  }, demoTask).then(result => ({ rows:result.value,demo:result.demo }))
}

export const hub = {
  sources: () => read(sourceApi.list, 'sources'),
  source: async (id) => {
    if (!forceMock) { try { return { value: await sourceApi.get(id), demo: false } } catch (e) { if (!missing(e)) throw e } }
    return { value: demoStore.list('sources').find((r) => r.source_id === id), demo: true }
  },
  saveSource: (form, id, demoMode=false) => sourceWrite(() => id ? sourceApi.update(id, form) : sourceApi.create(form), () => {
    const old = demoStore.list('sources').find((r) => r.source_id === id)
    const { password, ...safe } = form
    return demoStore.put('sources', { ...old, ...safe, source_id: id || nextDemoId('SRC'), connection_mode: 'DYNAMIC', updated_at: new Date().toISOString(), status: safe.status || old?.status || 'ENABLED', _demo: true }, 'source_id')
  }, demoMode),
  sourceStatus: (id, status, demoMode=false) => sourceWrite(() => sourceApi.status(id, status), () => {
    const row=demoStore.list('sources').find(r=>r.source_id===id)
    return demoStore.put('sources', { ...row, status }, 'source_id')
  }, demoMode),
  testSource: (source) => sourceWrite(() => sourceApi.test(source.source_id), () => ({ connected: true, database_product: 'MySQL', database_version: '8.0.33（演示）', response_time_ms: 18, reason: '演示连接结果' }),source._demo===true),
  tasks: () => read(metadataApi.list, 'tasks'),
  tables: taskId => taskRead(taskId, () => metadataApi.tables(taskId), () => demoStore.list('tables').filter(r => r.task_id === taskId)),
  fieldsForTask: (taskId, table) => taskRead(taskId, () => metadataApi.fields(taskId, table), () => table === 'container_event' ? demoStore.list('fields') : []),
  startScan: (source) => sourceWrite(() => metadataApi.start(source.source_id), () => {
    const task = { task_id: nextDemoId('SCAN'), source_id: source.source_id, source_name: source.source_name, status: 'SUCCESS', table_count: 1, field_count: 12, started_at: new Date().toISOString(), finished_at: new Date().toISOString(), _demo: true }
    demoStore.put('tasks', task, 'task_id')
    demoStore.put('tables', { ...demoStore.list('tables')[0], task_id: task.task_id }, 'task_id')
    return task
  },source._demo===true),
  resources: () => read(resourceApi.list, 'resources'),
  resource: id => resourceRead(id, () => resourceApi.get(id), () => demoStore.list('resources').find(r => r.resource_id === id)),
  saveResource: (form, id) => sourceWrite(() => id ? resourceApi.update(id, form) : resourceApi.create(form), () => {
    if (id ? !isDemoResource(id) : !isDemoSource(form.source_id)) throw new Error('请从演示扫描结果选择来源表')
    const old = demoStore.list('resources').find((r) => r.resource_id === id)
    return demoStore.put('resources', { ...old, ...form, source_name:demoStore.list('sources').find(s=>s.source_id===form.source_id)?.source_name || '', resource_id: id || nextDemoId('RES'), status: old?.status || 'DRAFT', version: old ? old.version + 1 : 1, updated_at: new Date().toISOString(), _demo: true }, 'resource_id')
  }, id ? isDemoResource(id) : isDemoSource(form.source_id)),
  publishResource: (id) => sourceWrite(() => resourceApi.publish(id), () => {
    if (!isDemoResource(id)) throw new Error('演示资源不存在')
    const current = demoStore.list('resources').find((r) => r.resource_id === id)
    if (current.status !== 'DRAFT') throw new Error('仅草稿资源可以发布')
    return demoStore.put('resources', { ...current, status: 'PUBLISHED', version: current.version + 1, updated_at:new Date().toISOString() }, 'resource_id')
  }, isDemoResource(id)),
  resourceFields: id => resourceRead(id, () => resourceApi.fields(id), () => demoStore.resourceFields(id), true),
  saveField: (id, field, changes) => sourceWrite(() => resourceApi.updateField(id, field, changes), () =>
    demoStore.saveResourceField(id, field, changes), isDemoResource(id)),
  capabilities: () => read(capabilityApi.list, 'capabilities'),
  resourceCapabilities: id => resourceRead(id, () => capabilityApi.byResource(id), () => demoStore.list('capabilities').filter(r => r.resource_id === id), true),
  saveCapability: (form, id, demoMode=false) => sourceWrite(() => id ? capabilityApi.update(id, form) : capabilityApi.create(form), () => {
    const old = demoStore.list('capabilities').find((r) => r.capability_id === id)
    return demoStore.put('capabilities', { ...old, ...form, capability_id: id || nextDemoId('CAP'), status: old?.status || 'DRAFT', health_status: old?.health_status || 'UNKNOWN', version: old?.version || 1, _demo: true }, 'capability_id')
  }, demoMode),
  capabilityAction: (id, action, demoMode=false) => sourceWrite(() => capabilityApi[action](id), () => {
    const old = demoStore.list('capabilities').find((r) => r.capability_id === id)
    const status = action === 'verify' ? old.status === 'DISABLED' ? 'DISABLED' : 'VERIFIED' : action === 'enable' ? 'ENABLED' : 'DISABLED'
    return demoStore.put('capabilities', { ...old, status, health_status: action === 'verify' ? 'UP' : old.health_status, version: old.version + 1 }, 'capability_id')
  }, demoMode),
  capabilityRuntime: (id, demoMode=false) => sourceWrite(() => capabilityApi.runtime(id), () => {
    const row=demoStore.list('capabilities').find(r=>r.capability_id===id)
    return { capability_id:id,enabled:row.status==='ENABLED',reason:row.status==='ENABLED'?'演示能力已启用':'演示能力未启用或已停用' }
  }, demoMode),
  capabilityInvocation: (id, demoMode=false) => sourceWrite(() => capabilityApi.invocation(id),
    () => demoInvocation(demoStore.list('capabilities').find(r => r.capability_id === id)), demoMode),
}
