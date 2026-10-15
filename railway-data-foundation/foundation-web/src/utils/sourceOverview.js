import { fetchDashboardList } from './dashboard.js'
import { parseUtcTime } from './time.js'

// Explicit display fields: never spread credentials or future sensitive API columns.
export function sourceOverviewInfo(source) {
  const keys=['source_id','source_name','source_type','host','port','database_name','username',
    'ssl_mode','status','connection_mode','description','updated_at','_demo']
  return Object.fromEntries(keys.map(key=>[key,source[key]]))
}
export function sourceOverviewRows(result,source,key,demo) {
  const id=key==='tasks'?'task_id':'resource_id'
  if(!['tasks','resources'].includes(key) || (source._demo===true)!==demo || !Array.isArray(result.rows)
    || result.rows.some(row=>!row || typeof row[id]!=='string' || !row[id]
      || typeof row.source_id!=='string' || !row.source_id || (row._demo===true)!==demo))
    throw new Error('关联元数据结构或模式不一致')
  const seen=new Set()
  for(const row of result.rows){if(seen.has(row[id]))throw new Error('关联编号重复');seen.add(row[id])}
  return result.rows.filter(row=>row.source_id===source.source_id).sort((a,b)=>{
    const time=row=>key==='tasks'?row.started_at:row.created_at
    return (parseUtcTime(time(b))?.getTime() || 0)-(parseUtcTime(time(a))?.getTime() || 0) || b[id].localeCompare(a[id])
  })
}
export async function readSourceOverview(key,source,fetchPage,{getDemoRows,...paging}={}) {
  const label=key==='tasks'?'关联扫描任务':'关联资源', demo=source._demo===true
  try {
    if(!['tasks','resources'].includes(key) || typeof source.source_id!=='string' || !source.source_id)
      throw new Error('invalid overview context')
    const result=demo?{rows:getDemoRows(key),complete:true,notice:''}
      :await fetchDashboardList(fetchPage,key==='tasks'?'task_id':'resource_id',paging)
    return {...result,rows:sourceOverviewRows(result,source,key,demo),error:false,demo}
  } catch {
    return {rows:[],complete:false,error:true,demo,notice:label+'读取失败或归属结构不一致，请重试'}
  }
}
