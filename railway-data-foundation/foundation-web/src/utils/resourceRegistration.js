const originKeys=['source_id','schema_name','physical_table']
export function registrationQuery(query={}) {
  const values={task_id:query.task,source_id:query.source,schema_name:query.schema,physical_table:query.fromTable}
  for(const [key,value] of Object.entries(values))
    if(typeof value!=='string' || !value.trim() || value.length>(['schema_name','physical_table'].includes(key)?64:120))
      throw new Error('扫描入口不完整，请返回扫描工作台重新选择表')
  return values
}
export async function verifyRegistrationOrigin(query,{task,tables}) {
  const selected=registrationQuery(query),result=await task(selected.task_id),scan=result.value
  if(!scan || scan.task_id!==selected.task_id || scan.source_id!==selected.source_id || scan.status!=='SUCCESS')
    throw new Error('扫描任务与来源不一致或尚未成功，请重新选择')
  const response=await tables(selected.task_id)
  if(response.demo!==result.demo || !Array.isArray(response.rows))throw new Error('扫描结果数据来源不一致，请重新加载')
  const candidates=response.rows.filter(row=>row?.schema_name===selected.schema_name && row.table_name===selected.physical_table)
  if(candidates.length!==1)throw new Error('所选表不在该任务的扫描结果中，请返回重新选择')
  const table=candidates[0]
  return {...selected,source_name:scan.source_name || scan.source_id,demo:result.demo,
    table_comment:table.table_comment || '',field_count:table.field_count,estimated_rows:table.estimated_rows}
}
export function existingRegisteredResource(rows,origin,demo) {
  if(!origin)return null
  return rows.find(row=>(row._demo===true)===demo && originKeys.every(key=>row[key]===origin[key])) || null
}
export function registrationEditScope(scope,form,rows,demo) {
  if(scope?.status==='RETIRED')return '已退役资源只读，不能编辑'
  const row=rows.find(item=>item.resource_id===scope?.resource_id)
  if(!row || row.version!==scope.version || row.status!==scope.status || (row._demo===true)!==demo)
    return '资源信息已变化，请刷新后重新打开编辑'
  if(![...originKeys,'scan_task_id'].every(key=>(form[key]??null)===(scope[key]??null) && (row[key]??null)===(scope[key]??null)) || form.resource_type!=='TABLE')
    return '资源来源、扫描任务和物理表不可更改，请重新打开编辑'
  return ''
}
export function registrationCreateScope(origin,form,demo) {
  if(!origin || origin.demo!==demo || !origin.task_id || form.scan_task_id!==origin.task_id || !originKeys.every(key=>form[key]===origin[key]) || form.resource_type!=='TABLE')
    return '扫描来源尚未核对或已经变化，请重新选择表'
  return ''
}
