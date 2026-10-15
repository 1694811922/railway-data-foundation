import { sourceApi } from '../api/source'
import { metadataApi } from '../api/metadata'
import { resourceApi } from '../api/resource'
import { capabilityApi } from '../api/capability'
import { canUseDemo } from './request'
import { demoStore, demoResourceConflict, nextDemoId, demoInvocation, isDemoResource, isDemoSource, isDemoCapability, isDemoTask } from '../mock/demoData'
import { dashboardModules, loadDashboard } from './dashboard.js'
import { loadResourceCatalog } from './resourceCatalog.js'
import { loadCapabilityCatalog } from './capabilityCatalog.js'
import { loadScanCatalog } from './scanCatalog.js'
import { loadSourceCatalog } from './sourceCatalog.js'
import { readSourceOverview } from './sourceOverview.js'
import { demoComparisonCandidates,demoComparison } from '../mock/schemaComparisonData.js'

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
  if(demoMode&&!forceMock)throw new Error('演示数据仅可在显式演示模式使用')
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
  const demoTask = isDemoTask(id)
  return sourceWrite(apiCall, () => {
    if (!demoTask) throw new Error('演示扫描任务不存在，请重新选择任务')
    return fallback()
  }, demoTask).then(result => ({ rows:result.value,demo:result.demo }))
}

export const hub = {
  dashboard: async () => {
    if (forceMock) return { demo:true, data:Object.fromEntries(dashboardModules.map(({key})=>
      [key, { rows:demoStore.list(key), complete:true, error:false, notice:'' }])) }
    // Dashboard totals never silently combine live metadata with demo fixtures.
    return { demo:false, data:await loadDashboard({ sources:sourceApi.list, tasks:metadataApi.list,
      resources:resourceApi.list, capabilities:capabilityApi.list }) }
  },
  sources: () => read(sourceApi.list, 'sources'),
  sourceCatalog: () => loadSourceCatalog(sourceApi.list,{demo:forceMock,getDemoRows:()=>demoStore.list('sources')}),
  sourceOverview: (source,key) => {
    if(source._demo===true&&!forceMock) return Promise.reject(new Error('演示数据仅可在显式演示模式使用'))
    if(forceMock&&source._demo!==true) return Promise.reject(new Error('演示模式不能读取真实数据源'))
    return readSourceOverview(key,source,key==='tasks'?metadataApi.list:resourceApi.list,
      {getDemoRows:name=>demoStore.list(name)})
  },
  source: async (id) => {
    if (!forceMock) { try { return { value: await sourceApi.get(id), demo: false } } catch (e) { if (!missing(e)) throw e } }
    return { value: demoStore.list('sources').find((r) => r.source_id === id), demo: true }
  },
  saveSource: (form, id, demoMode=false) => sourceWrite(() => id ? sourceApi.update(id, form) : sourceApi.create(form), () => {
    if(id&&!isDemoSource(id))throw new Error('演示数据源不存在，请重新选择')
    const old = demoStore.list('sources').find((r) => r.source_id === id)
    const { password, ...safe } = form
    return demoStore.put('sources', { ...old, ...safe, source_id: id || nextDemoId('SRC'), connection_mode: 'DYNAMIC', updated_at: new Date().toISOString(), status: safe.status || old?.status || 'ENABLED', _demo: true }, 'source_id')
  }, demoMode),
  sourceStatus: (id, status, demoMode=false) => sourceWrite(() => sourceApi.status(id, status), () => {
    if(!isDemoSource(id))throw new Error('演示数据源不存在，请重新选择')
    const row=demoStore.list('sources').find(r=>r.source_id===id)
    return demoStore.put('sources', { ...row, status }, 'source_id')
  }, demoMode),
  deleteSource: (id, demoMode=false) => sourceWrite(() => sourceApi.delete(id), () => {
    if(!isDemoSource(id))throw new Error('演示数据源不存在，请重新选择')
    const row=demoStore.list('sources').find(item=>item.source_id===id)
    if(row.status!=='DISABLED')throw new Error('请先停用数据源，再执行删除')
    if(demoStore.list('tasks').some(task=>task.source_id===id))throw new Error('数据源已有扫描记录，不能删除；可保持停用以保留历史')
    return demoStore.remove('sources',id,'source_id')
  }, demoMode),
  testSource: (source) => sourceWrite(() => sourceApi.test(source.source_id), () => {
    if(!isDemoSource(source.source_id)||source.status!=='ENABLED')throw new Error('请选择已启用的演示数据源')
    return { connected: true, database_product: 'MySQL', database_version: '8.0.33（演示）', response_time_ms: 18, reason: '演示连接结果' }
  },source._demo===true),
  tasks: () => read(metadataApi.list, 'tasks'),
  scanCatalog: taskId => loadScanCatalog({tasks:metadataApi.list,sources:sourceApi.list},
    {demo:forceMock,getDemoRows:key=>demoStore.list(key)}),
  scanTask: id => sourceWrite(()=>metadataApi.task(id),()=>{
    if(!isDemoTask(id)) throw new Error('演示扫描任务不存在，请重新选择')
    return demoStore.list('tasks').find(row=>row.task_id===id)
  },isDemoTask(id)),
  tables: taskId => taskRead(taskId, () => metadataApi.tables(taskId), () => demoStore.list('tables').filter(r => r.task_id === taskId)),
  fieldsForTask: (taskId, table) => taskRead(taskId, () => metadataApi.fields(taskId, table), () => table === 'container_event' ? demoStore.list('fields') : []),
  startScan: (source) => sourceWrite(() => metadataApi.start(source.source_id), () => {
    const task = { task_id: nextDemoId('SCAN'), source_id: source.source_id, source_name: source.source_name, status: 'SUCCESS', table_count: 1, field_count: 12, started_at: new Date().toISOString(), finished_at: new Date().toISOString(), _demo: true }
    demoStore.put('tasks', task, 'task_id')
    demoStore.put('tables', { ...demoStore.list('tables')[0], task_id: task.task_id }, 'task_id')
    return task
  },source._demo===true),
  deleteTask: (id, demoMode=false) => sourceWrite(() => metadataApi.delete(id), () => {
    if(!isDemoTask(id))throw new Error('演示扫描任务不存在，请重新选择')
    const task=demoStore.list('tasks').find(row=>row.task_id===id)
    if(['PENDING','RUNNING'].includes(task.status))throw new Error('任务正在执行，不能删除')
    const tables=demoStore.list('tables').filter(table=>table.task_id===id)
    const used=demoStore.list('resources').some(resource=>resource.source_id===task.source_id && tables.some(table=>table.table_name===resource.physical_table && table.schema_name===resource.schema_name))
    if(used)throw new Error('扫描任务已用于创建资源，不能删除')
    demoStore.replace('tables',demoStore.list('tables').filter(table=>table.task_id!==id))
    return demoStore.remove('tasks',id,'task_id')
  }, demoMode),
  resources: () => read(resourceApi.list, 'resources'),
  resourceCatalog: (options={}) => loadResourceCatalog(resourceApi.list, { ...options, forceMock,
    getDemoRows:()=>demoStore.list('resources'),canUseDemo }),
  resource: id => resourceRead(id, () => resourceApi.get(id), () => demoStore.list('resources').find(r => r.resource_id === id)),
  resourceVersions: (id,params) => resourceRead(id,()=>resourceApi.versions(id,params),()=>demoStore.resourceVersions(id,params)),
  resourceVersion: (id,version) => resourceRead(id,()=>resourceApi.version(id,version),()=>demoStore.resourceVersion(id,version)),
  comparisonScans: (id,params) => resourceRead(id,()=>resourceApi.comparisonScans(id,params),()=>demoComparisonCandidates(id,params)),
  compareSchema: (id,task) => resourceRead(id,()=>resourceApi.compareSchema(id,task),()=>demoComparison(id,task)),
  saveResource: (form, id) => sourceWrite(() => id ? resourceApi.update(id, form) : resourceApi.create(form), () => {
    if (id ? !isDemoResource(id) : !isDemoSource(form.source_id)) throw new Error('请从演示扫描结果选择来源表')
    const old = demoStore.list('resources').find((r) => r.resource_id === id)
    if(id && form.version!==old?.version)throw demoResourceConflict()
    return demoStore.put('resources', { ...old, ...form, source_name:demoStore.list('sources').find(s=>s.source_id===form.source_id)?.source_name || '', resource_id: id || nextDemoId('RES'), status: old?.status || 'DRAFT', version: old ? old.version + 1 : 1, updated_at: new Date().toISOString(), _demo: true }, 'resource_id')
  }, id ? isDemoResource(id) : isDemoSource(form.source_id)),
  publishResource: (id) => sourceWrite(() => resourceApi.publish(id), () => {
    if (!isDemoResource(id)) throw new Error('演示资源不存在')
    const current = demoStore.list('resources').find((r) => r.resource_id === id)
    if (current.status !== 'DRAFT') throw new Error('仅草稿资源可以发布')
    return demoStore.put('resources', { ...current, status: 'PUBLISHED', version: current.version + 1, updated_at:new Date().toISOString() }, 'resource_id')
  }, isDemoResource(id)),
  deleteResource: id => sourceWrite(() => resourceApi.delete(id), () => {
    if(!isDemoResource(id))throw new Error('演示资源不存在')
    const row=demoStore.list('resources').find(item=>item.resource_id===id)
    if(row.status!=='DRAFT')throw new Error('仅草稿资源可以删除')
    if(demoStore.list('capabilities').some(item=>item.resource_id===id))throw new Error('资源已关联接口能力，不能删除')
    return demoStore.remove('resources',id,'resource_id')
  }, isDemoResource(id)),
  resourceFields: id => resourceRead(id, () => resourceApi.fields(id), () => demoStore.resourceFields(id), true),
  saveField: (id, field, changes) => sourceWrite(() => resourceApi.updateField(id, field, changes), () =>
    demoStore.saveResourceField(id, field, changes), isDemoResource(id)),
  capabilities: () => read(capabilityApi.list, 'capabilities'),
  capabilityCatalog: (resourceId) => loadCapabilityCatalog({capabilities:capabilityApi.list,resources:resourceApi.list},
    {demo:forceMock,getDemoRows:name=>demoStore.list(name)}),
  resourceCapabilities: id => resourceRead(id, () => capabilityApi.byResource(id), () => demoStore.list('capabilities').filter(r => r.resource_id === id), true),
  saveCapability: (form, id, demoMode=false) => sourceWrite(() => id ? capabilityApi.update(id, form) : capabilityApi.create(form), () => {
    if(!isDemoResource(form.resource_id) || (id && !isDemoCapability(id))) throw new Error('演示能力只能关联当前浏览器中的演示资源')
    const resource=demoStore.list('resources').find(row=>row.resource_id===form.resource_id)
    if(resource.status!=='PUBLISHED') throw new Error('只能关联已发布资源')
    const old = demoStore.list('capabilities').find((r) => r.capability_id === id)
    if(old && old.status!=='DRAFT') throw new Error('仅草稿能力可以编辑')
    if(demoStore.list('capabilities').some(row=>row.resource_id===form.resource_id && row.capability_id!==id && row.capability_name===form.capability_name)) throw new Error('同一资源下能力名称不能重复')
    return demoStore.put('capabilities', { ...old, ...form, resource_name:resource.resource_name, capability_id: id || nextDemoId('CAP'), status: old?.status || 'DRAFT', health_status: old?.health_status || 'UNKNOWN', version: old ? old.version+1 : 1, _demo: true }, 'capability_id')
  }, demoMode),
  capabilityAction: (id, action, demoMode=false) => sourceWrite(() => capabilityApi[action](id), () => {
    if(!isDemoCapability(id)) throw new Error('演示能力不存在，请重新选择')
    const old = demoStore.list('capabilities').find((r) => r.capability_id === id)
    const status = action === 'verify' ? old.status === 'DISABLED' ? 'DISABLED' : 'VERIFIED' : action === 'enable' ? 'ENABLED' : 'DISABLED'
    return demoStore.put('capabilities', { ...old, status, health_status: action === 'verify' ? 'UP' : old.health_status, version: old.version + 1 }, 'capability_id')
  }, demoMode),
  deleteCapability: (id, demoMode=false) => sourceWrite(() => capabilityApi.delete(id), () => {
    if(!isDemoCapability(id))throw new Error('演示能力不存在，请重新选择')
    const row=demoStore.list('capabilities').find(item=>item.capability_id===id)
    if(row.status!=='DRAFT')throw new Error('仅草稿能力可以删除')
    return demoStore.remove('capabilities',id,'capability_id')
  }, demoMode),
  capabilityRuntime: (id, demoMode=false) => sourceWrite(() => capabilityApi.runtime(id), () => {
    const row=demoStore.list('capabilities').find(r=>r.capability_id===id)
    return { capability_id:id,enabled:row.status==='ENABLED',reason:row.status==='ENABLED'?'演示能力已启用':'演示能力未启用或已停用' }
  }, demoMode),
  capabilityKafkaRuntime: (id, demoMode=false) => sourceWrite(() => capabilityApi.kafkaRuntime(id), () => {
    if(!isDemoCapability(id))throw new Error('演示能力不存在')
    const row=demoStore.list('capabilities').find(r=>r.capability_id===id)
    return {capability_id:id,type:row.type,status:row.status,topic:row.topic,auth_type:row.auth_type,resource_id:row.resource_id,source_host:null,source_port:null,database_name:null,physical_table:null,enabled:row.status==='ENABLED',reason:'演示发送状态，不执行真实消息发送或订阅授权'}
  }, demoMode),
  capabilityInvocation: (id, demoMode=false) => sourceWrite(() => capabilityApi.invocation(id),
    () => {
      if(!isDemoCapability(id)) throw new Error('演示能力不存在，请重新选择')
      return demoInvocation(demoStore.list('capabilities').find(r => r.capability_id === id))
    }, demoMode),
}
