// DEMO FALLBACK: only used when an endpoint is absent/unreachable, or VITE_USE_MOCK=true.
// These records never claim to represent Foundation DB state.
import {demoVersionSnapshot,demoVersionList,demoVersionDetail} from './resourceVersionData.js'
const today = new Date().toISOString().slice(0, 10)
export const demoSeed = {
  sources: [{ source_id: 'SRC_DEMO_GZ_PORT', source_name: '广州港业务数据库', source_type: 'MYSQL', connection_mode: 'DYNAMIC', ssl_mode: 'DISABLED', host: '127.0.0.1', port: 13306, database_name: 'port_source_db', username: 'source_app', status: 'ENABLED', updated_at: `${today} 09:18:00`, description: '广州港集装箱作业事件模拟数据源', _demo: true }],
  tasks: [{ task_id: 'SCAN_DEMO_001', source_id: 'SRC_DEMO_GZ_PORT', source_name: '广州港业务数据库', status: 'SUCCESS', table_count: 1, field_count: 12, started_at: `${today} 09:20:00`, finished_at: `${today} 09:20:03`, _demo: true }],
  tables: [{ task_id: 'SCAN_DEMO_001', schema_name: 'port_source_db', table_name: 'container_event', table_comment: '广州港集装箱作业事件', field_count: 12, estimated_rows: 20, status: '待注册', _demo: true }],
  fields: [
    ['id', 'bigint', '事件主键', true, false], ['container_no', 'varchar(20)', '集装箱号', false, false],
    ['event_type', 'varchar(20)', '作业类型', false, false], ['event_time', 'datetime(3)', '作业发生时间', false, true],
    ['port_code', 'varchar(20)', '港口代码', false, false], ['yard_code', 'varchar(30)', '堆场代码', false, false],
    ['train_no', 'varchar(30)', '海铁联运班列号', false, false], ['operation_status', 'varchar(20)', '作业状态', false, false],
    ['equipment_no', 'varchar(30)', '作业设备编号', false, false], ['source_system', 'varchar(50)', '源业务系统标识', false, false],
    ['created_at', 'datetime(3)', '创建时间', false, true], ['updated_at', 'datetime(3)', '更新时间', false, true],
  ].map(([field_name, data_type, technical_comment, primary_key, time_field]) => ({ field_name, data_type, technical_comment, primary_key, time_field, nullable: !primary_key, default_value: '—', business_name: technical_comment, business_description: '', sensitivity_level: '内部', _demo: true })),
  resources: [{ resource_id: 'RES_000001', scan_task_id: 'SCAN_DEMO_001', resource_name: '广州港集装箱作业事件数据集', source_id: 'SRC_DEMO_GZ_PORT', source_name: '广州港业务数据库', schema_name: 'port_source_db', physical_table: 'container_event', resource_type: 'TABLE', business_description: '汇集广州港集装箱进出闸、装卸及班列联运作业事件', owner_department: '广州港数据管理部', security_level: '内部', update_mode: '实时', status: 'PUBLISHED', version: 1, updated_at: `${today} 09:32:00`, _demo: true }],
  capabilities: [
    { capability_id: 'CAP_000001', capability_name: '集装箱事件查询接口', resource_id: 'RES_000001', resource_name: '广州港集装箱作业事件数据集', type: 'REST_API', http_method: 'GET', endpoint: '/containers/events', protocol: 'HTTP', auth_type: 'TOKEN', description: '按箱号、班列及时间范围查询事件', status: 'ENABLED', health_status: 'UP', version: 1, _demo: true },
    { capability_id: 'CAP_000002', capability_name: '集装箱实时事件Topic', resource_id: 'RES_000001', resource_name: '广州港集装箱作业事件数据集', type: 'KAFKA_TOPIC', topic: 'port.container.event.v1', protocol: 'KAFKA', auth_type: 'SASL', description: '实时推送港口作业事件', status: 'ENABLED', health_status: 'UP', version: 1, _demo: true },
    { capability_id: 'CAP_000003', capability_name: '集装箱CDC变化流', resource_id: 'RES_000001', resource_name: '广州港集装箱作业事件数据集', type: 'CDC_STREAM', source_table: 'container_event', topic: 'port.container.event.cdc', protocol: 'DEBEZIUM', description: '源表变更事件流', status: 'DRAFT', health_status: 'UNKNOWN', version: 1, _demo: true },
  ],
}

const storageKey = 'foundation_frontend_demo_v1'
function normalize(state) {
  state.sources=(state.sources || []).map(({password, database, connection_env_prefix, ...row})=>({
    ...row, database_name:row.database_name || database, connection_mode:'DYNAMIC',
    ssl_mode:row.ssl_mode || 'DISABLED', status:['ENABLED','DISABLED'].includes(row.status)?row.status:'ENABLED'
  }))
  state.resource_fields ||= {}
  state.resource_versions ||= {}
  for (const resource of state.resources || []) {
    state.resource_fields[resource.resource_id] ||= resource.physical_table === 'container_event'
      ? structuredClone(state.fields || demoSeed.fields) : []
    state.resource_versions[resource.resource_id] ||= [demoVersionSnapshot(resource,state.resource_fields[resource.resource_id])]
  }
  return state
}
function read() {
  try {
    const state=JSON.parse(localStorage.getItem(storageKey)) || structuredClone(demoSeed)
    return normalize(state)
  }
  catch { return normalize(structuredClone(demoSeed)) }
}
export const demoStore = {
  list: (name) => read()[name] || [],
  put(name, entry, idKey) {
    const state = read()
    const index = state[name].findIndex((row) => row[idKey] === entry[idKey])
    if(name==='resources'){
      const previous=state[name][index]
      state.resource_fields[entry.resource_id] ||= entry.physical_table==='container_event'?structuredClone(state.fields || demoSeed.fields):[]
      state.resource_versions[entry.resource_id] ||= []
      state.resource_versions[entry.resource_id].push(demoVersionSnapshot(entry,state.resource_fields[entry.resource_id],
        !previous?'CREATE':previous.status==='DRAFT'&&entry.status==='PUBLISHED'?'PUBLISH':'RESOURCE_UPDATE',null,entry.updated_at))
    }
    if (index < 0) state[name].unshift(entry)
    else state[name][index] = entry
    localStorage.setItem(storageKey, JSON.stringify(state))
    return entry
  },
  replace(name, values) { const state = read(); state[name] = values; localStorage.setItem(storageKey, JSON.stringify(state)) },
  remove(name, id, idKey) {
    const state = read()
    const index = state[name].findIndex(row => row[idKey] === id)
    if (index < 0) throw new Error('演示记录不存在，请刷新后重试')
    const [removed] = state[name].splice(index, 1)
    if (name === 'resources') {delete state.resource_fields[id];delete state.resource_versions[id]}
    localStorage.setItem(storageKey, JSON.stringify(state))
    return removed
  },
  resourceFields(id) {
    const state=read(),version=state.resources.find(row=>row.resource_id===id)?.version
    return (state.resource_fields[id] || []).map(row=>({...row,resource_version:version}))
  },
  resourceVersions(id,params){return demoVersionList(read(),id,params)},
  resourceVersion(id,version){return demoVersionDetail(read(),id,version)},
  saveResourceField(id, fieldName, changes) {
    const state = read()
    const resource = state.resources.find(r => r.resource_id === id)
    const field = state.resource_fields[id]?.find(f => f.field_name === fieldName)
    if (!resource || !field) throw new Error('演示资源字段不存在，请重新选择资源')
    if(changes.resource_version!==resource.version)throw demoResourceConflict()
    const {resource_version,...attributes}=changes
    Object.assign(field, attributes)
    resource.version += 1
    resource.updated_at = new Date().toISOString()
    state.resource_versions[id].push(demoVersionSnapshot(resource,state.resource_fields[id],'FIELD_UPDATE',fieldName,resource.updated_at))
    localStorage.setItem(storageKey, JSON.stringify(state))
    return {...field,resource_version:resource.version}
  },
}

export function demoResourceConflict() {
  const message='资源已被修改，请刷新后重新编辑；本次修改未保存'
  return Object.assign(new Error(message),{response:{status:409,data:{code:10009,message,data:null}}})
}

// Real backend IDs are UUIDs. Only owned demo IDs can use browser data without a global mock switch.
export const isDemoResource = id => /^RES_(?:DEMO_[A-Z0-9]+|[0-9]{6})$/.test(id || '')
  && demoStore.list('resources').some(r => r.resource_id === id && r._demo === true)
export const isDemoSource = id => /^SRC_DEMO_[A-Z0-9_]+$/.test(id || '')
  && demoStore.list('sources').some(r => r.source_id === id && r._demo === true)
export const isDemoCapability = id => /^CAP_(?:DEMO_[A-Z0-9]+|[0-9]{6})$/.test(id || '')
  && demoStore.list('capabilities').some(r=>r.capability_id===id && r._demo===true)
export const isDemoTask = id => /^SCAN_DEMO_[A-Z0-9]+$/.test(id || '')
  && demoStore.list('tasks').some(row=>row.task_id===id && row._demo===true)

export function nextDemoId(prefix) { return `${prefix}_DEMO_${Date.now().toString(36).toUpperCase()}` }

// Same response shape as GET /capabilities/{id}/invocation. Display-only demo, not a working credential.
export function demoInvocation(row) {
  const enabled = row.status === 'ENABLED'
  return {
    capability_id: row.capability_id, http_method: row.http_method,
    provider_base_url: 'http://127.0.0.1:18081/', endpoint: row.endpoint,
    request_url: 'http://127.0.0.1:18081' + row.endpoint,
    header_name: 'X-Capability-Id', header_value: row.capability_id,
    enabled, reason: enabled ? '演示能力已启用' : '演示能力未启用或已停用',
  }
}
