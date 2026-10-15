import { parseUtcTime } from './time.js'
export const changeLabels={ADDED:'新增',REMOVED:'删除',MODIFIED:'修改'}
export const propertyLabels={data_type:'数据类型',column_type:'完整列类型',primary_key:'主键',nullable:'可空',
  default_value:'默认值',technical_comment:'技术注释',ordinal_position:'字段顺序',table_comment:'表注释'}
export const fieldProperties=Object.keys(propertyLabels).filter(key=>key!=='table_comment')
const integer=value=>Number.isInteger(value)&&value>=0
const reject=()=>{throw new Error('结构比较数据不完整或与当前资源不一致，请重新加载')}
function validateScan(scan,resource) {
  if(!scan || typeof scan.task_id!=='string' || !scan.task_id || scan.source_id!==resource.source_id
    || scan.status!=='SUCCESS' || !parseUtcTime(scan.started_at) || !parseUtcTime(scan.finished_at))reject()
}
export function comparisonCandidates(response,resource,demo,limit=20) {
  const value=response?.value
  if(response?.demo!==demo || !value || !Array.isArray(value.items) || !integer(value.total)
    || value.items.length>limit || value.total<value.items.length)reject()
  const seen=new Set()
  for(const scan of value.items){validateScan(scan,resource);if(scan.task_id===resource.scan_task_id || seen.has(scan.task_id))reject();seen.add(scan.task_id)}
  return value
}
function validateField(field,name) {
  if(!field || field.field_name!==name || typeof field.data_type!=='string' || !field.data_type || typeof field.column_type!=='string'
    || !field.column_type || typeof field.primary_key!=='boolean' || typeof field.nullable!=='boolean'
    || !(field.default_value===null || typeof field.default_value==='string') || typeof field.technical_comment!=='string'
    || !Number.isInteger(field.ordinal_position) || field.ordinal_position<1)reject()
}
export function comparisonResult(response,resource,taskId,demo) {
  const value=response?.value
  if(response?.demo!==demo || !value || value.resource_id!==resource.resource_id || value.source_id!==resource.source_id
    || value.schema_name!==resource.schema_name || value.physical_table!==resource.physical_table
    || !Number.isInteger(value.resource_version) || value.resource_version<1)reject()
  validateScan(value.baseline_scan,resource);validateScan(value.target_scan,resource)
  if(value.baseline_scan.task_id!==resource.scan_task_id || value.target_scan.task_id!==taskId || taskId===resource.scan_task_id
    || parseUtcTime(value.target_scan.started_at)<parseUtcTime(value.baseline_scan.started_at))reject()
  if(!Array.isArray(value.changes) || !Array.isArray(value.table_changes))reject()
  if(['TABLE_NOT_FOUND','SCOPE_UNCONFIRMED'].includes(value.comparison_status)) {
    if(value.summary!==null || value.changes.length || value.table_changes.length)reject()
    return value
  }
  if(!['UNCHANGED','CHANGED'].includes(value.comparison_status) || !value.summary
    || !['added','removed','modified','unchanged'].every(key=>integer(value.summary[key])))reject()
  const seen=new Set(),counts={ADDED:0,REMOVED:0,MODIFIED:0}
  for(const row of value.changes) {
    if(!row || !changeLabels[row.change_type] || typeof row.field_name!=='string' || !row.field_name || seen.has(row.field_name)
      || !Array.isArray(row.changed_properties) || new Set(row.changed_properties).size!==row.changed_properties.length
      || row.changed_properties.some(key=>!fieldProperties.includes(key)))reject()
    seen.add(row.field_name);counts[row.change_type]++
    if(row.change_type==='ADDED'){if(row.before!==null || row.changed_properties.length)reject();validateField(row.after,row.field_name)}
    else if(row.change_type==='REMOVED'){if(row.after!==null || row.changed_properties.length)reject();validateField(row.before,row.field_name)}
    else {
      validateField(row.before,row.field_name);validateField(row.after,row.field_name)
      const actual=fieldProperties.filter(key=>row.before[key]!==row.after[key])
      if(!actual.length || actual.length!==row.changed_properties.length || actual.some(key=>!row.changed_properties.includes(key)))reject()
    }
  }
  if(counts.ADDED!==value.summary.added || counts.REMOVED!==value.summary.removed || counts.MODIFIED!==value.summary.modified)reject()
  if(value.table_changes.length>1 || value.table_changes.some(change=>change?.property!=='table_comment'
    || typeof change.before!=='string' || typeof change.after!=='string' || change.before===change.after))reject()
  if((value.comparison_status==='UNCHANGED')!==(value.changes.length===0 && value.table_changes.length===0))reject()
  return value
}
export function filterChanges(rows,{keyword='',type=''}={}) {
  const terms=keyword.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row=>(!type || row.change_type===type) && terms.every(term=>[
    row.field_name,changeLabels[row.change_type],...row.changed_properties.map(key=>propertyLabels[key]),
    ...[row.before,row.after].flatMap(field=>field ? [field.data_type,field.column_type,field.technical_comment] : [])
  ].join(' ').toLocaleLowerCase().includes(term)))
}
export function propertyValue(field,key) {
  if(!field)return '—'
  const value=field[key]
  if(value===null)return '无默认值（NULL）'
  if(typeof value==='boolean')return value?'是':'否'
  return value===''?'空字符串':String(value)
}
export function fieldSummary(field) {
  return field ? `${field.column_type} · ${field.primary_key?'主键':'非主键'} · ${field.nullable?'可空':'不可空'}` : '—'
}
