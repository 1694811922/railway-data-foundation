import { beijingDay, parseUtcTime } from './time.js'

export const dashboardModules = [
  { key:'sources', label:'数据源', id:'source_id' },
  { key:'tasks', label:'扫描任务', id:'task_id' },
  { key:'resources', label:'数据资源', id:'resource_id' },
  { key:'capabilities', label:'接口能力', id:'capability_id' },
]
export const capabilityTypes = [
  { type:'REST_API', label:'REST 接口' },
  { type:'KAFKA_TOPIC', label:'Kafka 消息主题' },
  { type:'CDC_STREAM', label:'CDC 变化流' },
  { type:'READONLY_QUERY', label:'只读查询' },
]

// Existing APIs return arrays with limit/offset, not aggregate counts.
// Stop at a bounded number of metadata rows and label any incomplete count.
export async function fetchDashboardList(fetchPage, idKey, { pageSize=200, maxRows=5000 } = {}) {
  if (!Number.isInteger(pageSize) || pageSize<1 || pageSize>200 || !Number.isInteger(maxRows) || maxRows<1)
    throw new Error('统计分页配置不正确')
  const rows = [], ids = new Set()
  for (let offset=0; offset<=maxRows;) {
    const limit = Math.min(pageSize, maxRows-offset || 1)
    const page = await fetchPage({ limit, offset })
    if (!Array.isArray(page) || page.length>limit || page.some(row => !row || typeof row[idKey]!=='string' || !row[idKey]))
      throw new Error('列表响应格式不正确')
    if (offset===maxRows) return { rows, complete:page.length===0, notice:page.length ? `仅统计前 ${maxRows} 条记录` : '' }
    for (const row of page) {
      if (ids.has(row[idKey])) return { rows, complete:false, notice:'读取期间列表发生变化，请刷新确认' }
      ids.add(row[idKey]); rows.push(row)
    }
    if (page.length<limit) return { rows, complete:true, notice:'' }
    offset += limit
  }
}

export async function loadDashboard(fetchers, options) {
  const results = await Promise.allSettled(dashboardModules.map(module =>
    fetchDashboardList(fetchers[module.key], module.id, options)))
  return Object.fromEntries(dashboardModules.map((module, index) => [module.key,
    results[index].status==='fulfilled'
      ? { ...results[index].value, error:false }
      : { rows:[], complete:false, error:true, notice:`${module.label}读取失败，请检查服务后重试` },
  ]))
}

export function dashboardMetrics(data, now=new Date()) {
  const count = (keys, predicate) => keys.some(key => !data[key] || data[key].error) ? null : predicate()
  const metric = (key, label, dependencies, predicate, note, path) => {
    const value = count(dependencies, predicate)
    return { key, label, value, partial:value!==null && dependencies.some(dep => !data[dep].complete), note, path }
  }
  const sources = data.sources?.rows || [], tasks = data.tasks?.rows || []
  const resources = data.resources?.rows || [], capabilities = data.capabilities?.rows || []
  const enabledSources = new Set(sources.filter(row=>row.status==='ENABLED').map(row=>row.source_id))
  const publishedResources = new Set(resources.filter(row=>row.status==='PUBLISHED' && enabledSources.has(row.source_id)).map(row=>row.resource_id))
  return [
    metric('sources','已接入数据源',['sources'],()=>sources.length,'已登记的外部数据源','/sources'),
    metric('resources','已发布数据资源',['resources'],()=>resources.filter(row=>row.status==='PUBLISHED').length,'状态为已发布的资源','/resources'),
    metric('capabilities','已注册接口能力',['capabilities'],()=>capabilities.length,'已登记的各类访问方式','/capabilities'),
    metric('available','当前可用能力',['capabilities','resources','sources'],()=>capabilities.filter(row=>row.status==='ENABLED' && row.health_status==='UP' && publishedResources.has(row.resource_id)).length,'启用、最近验证正常且来源启用','/capabilities'),
    metric('today','今日扫描任务',['tasks'],()=>tasks.filter(row=>beijingDay(row.started_at)===beijingDay(now)).length,'按北京时间的启动日期统计','/scans'),
    metric('abnormal','异常能力',['capabilities'],()=>capabilities.filter(row=>['DOWN','DEGRADED'].includes(row.health_status)).length,'最近验证为异常或降级','/capabilities'),
  ]
}

export function recentResources(rows) {
  return [...rows].sort((a,b)=>(parseUtcTime(b.created_at)?.getTime() || 0)-(parseUtcTime(a.created_at)?.getTime() || 0)
    || String(b.resource_id).localeCompare(String(a.resource_id))).slice(0,5)
}
