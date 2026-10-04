import { fetchDashboardList } from './dashboard.js'

// Tasks and selectable sources share one explicit mode; failures never borrow demo rows.
export async function loadScanCatalog(fetchers, { demo=false, getDemoRows, ...paging }={}) {
  const keys=['tasks','sources']
  if(demo) return {demo:true,...Object.fromEntries(keys.map(key=>
    [key,{rows:getDemoRows(key),complete:true,error:false,notice:''}]))}
  const results=await Promise.allSettled(keys.map(key=>fetchDashboardList(fetchers[key],key==='tasks'?'task_id':'source_id',paging)))
  return {demo:false,...Object.fromEntries(keys.map((key,index)=>[key,results[index].status==='fulfilled'
    ? {...results[index].value,error:false}
    : {rows:[],complete:false,error:true,notice:(key==='tasks'?'扫描任务':'数据源选项')+'读取失败，请刷新重试'}]))}
}
export function filterScanTasks(rows, {keyword='',source_id='',status=''}={}) {
  const terms=keyword.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row=>(!source_id || row.source_id===source_id) && (!status || row.status===status)
    && terms.every(term=>[row.task_id,row.source_id,row.source_name,row.failure_reason].join(' ').toLocaleLowerCase().includes(term)))
}
export function filterMetadataRows(rows, keyword, keys) {
  const terms=keyword.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row=>terms.every(term=>keys.map(key=>String(row[key] ?? '')).join(' ').toLocaleLowerCase().includes(term)))
}
export function scanSourceError(source,demo) {
  if(!source) return '请选择已读取的数据源'
  if((source._demo===true)!==demo) return '数据源与当前扫描模式不一致，请刷新后重新选择'
  if(source.status!=='ENABLED') return '数据源已停用，请先启用后再扫描'
  return ''
}
