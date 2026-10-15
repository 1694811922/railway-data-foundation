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

// Existing field responses omit ownership columns. Check them when supplied,
// and always bind the request/response mode and unique names to the selected task.
export function scanResultRows(result,task,demo,table=null) {
  if(!task || task.status!=='SUCCESS' || result?.demo!==demo || !Array.isArray(result.rows))
    throw new Error('扫描结果来源或结构不一致，请重新加载')
  const seen=new Set()
  for(const row of result.rows) {
    const name=row?.[table?'field_name':'table_name']
    if(typeof name!=='string' || !name.trim()
      || (!table && (typeof row.schema_name!=='string' || !row.schema_name.trim()))
      || (row.task_id!==undefined && row.task_id!==task.task_id)
      || (row.source_id!==undefined && row.source_id!==task.source_id)
      || (row._demo!==undefined && (row._demo===true)!==demo)
      || (table && ((row.table_name!==undefined && row.table_name!==table.table_name)
        || (row.schema_name!==undefined && row.schema_name!==table.schema_name))))
      throw new Error('扫描结果归属不一致，请重新加载')
    const key=table?name:JSON.stringify([row.schema_name,name])
    if(seen.has(key)) throw new Error('扫描结果名称重复，请重新加载')
    seen.add(key)
  }
  return result.rows
}

export function scanTableError(table,task,tables,demo) {
  if(!task || task.status!=='SUCCESS' || !table
    || (task._demo===true)!==demo
    || !tables.some(row=>row===table)) return '请先选择当前任务中已读取的表'
  return ''
}

export function scanResponseTask(result,source,demo) {
  const task=result?.value
  if(result?.demo!==demo || !task || typeof task.task_id!=='string' || !task.task_id.trim()
    || task.source_id!==source.source_id || (task._demo===true)!==demo
    || !['PENDING','RUNNING','SUCCESS','FAILED'].includes(task.status))
    throw new Error('扫描响应无法确认，请刷新任务检查，不要重复发起扫描')
  return task
}
