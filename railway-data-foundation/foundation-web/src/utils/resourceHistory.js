import {parseUtcTime} from './time.js'
export const historyActions={BASELINE:'启用时基线',CREATE:'资源登记',RESOURCE_UPDATE:'资源信息修改',FIELD_UPDATE:'字段维护',PUBLISH:'资源发布'}
export const resourceProperties={resource_name:'资源名称',business_description:'业务描述',owner_department:'责任单位',security_level:'安全等级',update_mode:'更新方式',status:'资源状态'}
export const historyFieldProperties={data_type:'数据类型',primary_key:'主键',technical_comment:'技术注释',ordinal_position:'字段顺序',business_name:'业务名称',business_description:'业务说明',time_field:'时间字段',sensitivity_level:'敏感等级'}
const invalid=()=>{throw new Error('版本历史数据不完整或与当前资源不一致，请重试')}
function entryValid(entry,id){
  if(!entry || entry.resource_id!==id || !Number.isInteger(entry.version) || entry.version<1 || !historyActions[entry.action]
    || !parseUtcTime(entry.recorded_at) || (entry.action==='FIELD_UPDATE' ? typeof entry.changed_field!=='string'||!entry.changed_field : entry.changed_field!==null))invalid()
}
export function historyList(response,id,demo,limit=20){
  const value=response?.value
  if(response?.demo!==demo || !Array.isArray(value?.items) || !Number.isInteger(value.total) || value.total<value.items.length || value.items.length>limit)invalid()
  let previous=Infinity
  for(const row of value.items){entryValid(row,id);if(row.version>=previous)invalid();previous=row.version}
  return value
}
function snapshotValid(snapshot,resource,version){
  if(!snapshot)invalid();entryValid(snapshot.entry,resource.resource_id)
  const value=snapshot.resource
  if(snapshot.entry.version!==version || !value || value.resource_id!==resource.resource_id || value.version!==version
    || value.source_id!==resource.source_id || value.scan_task_id!==resource.scan_task_id || value.schema_name!==resource.schema_name || value.physical_table!==resource.physical_table
    || !Array.isArray(snapshot.fields) || !['DRAFT','PUBLISHED'].includes(value.status))invalid()
  for(const key of [...Object.keys(resourceProperties),'source_name'])if(typeof value[key]!=='string')invalid()
  const names=new Set(),positions=new Set()
  for(const field of snapshot.fields){
    if(!field || typeof field.field_name!=='string' || !field.field_name || names.has(field.field_name) || field.resource_version!==version
      || !Number.isInteger(field.ordinal_position)||field.ordinal_position<1||positions.has(field.ordinal_position)
      || typeof field.primary_key!=='boolean'||typeof field.time_field!=='boolean')invalid()
    for(const key of ['data_type','technical_comment','business_name','business_description','sensitivity_level'])if(typeof field[key]!=='string')invalid()
    names.add(field.field_name);positions.add(field.ordinal_position)
  }
}
export function historyDetail(response,resource,version,demo){
  const value=response?.value
  if(response?.demo!==demo || !value || !('previous_snapshot' in value))invalid()
  snapshotValid(value.snapshot,resource,version)
  if(value.previous_snapshot!==null){if(version===1)invalid();snapshotValid(value.previous_snapshot,resource,version-1)}
  return value
}
export function historyChanges(value){
  if(!value?.previous_snapshot)return null
  const before=value.previous_snapshot,after=value.snapshot,rows=[]
  for(const [key,label] of Object.entries(resourceProperties))if(before.resource[key]!==after.resource[key])
    rows.push({scope:'资源信息',field_name:'—',property:label,before:before.resource[key],after:after.resource[key]})
  const old=new Map(before.fields.map(row=>[row.field_name,row])),current=new Map(after.fields.map(row=>[row.field_name,row]))
  for(const field of after.fields){
    const previous=old.get(field.field_name)
    if(!previous){rows.push({scope:'字段新增',field_name:field.field_name,property:'整个字段',before:null,after:field.data_type});continue}
    for(const [key,label] of Object.entries(historyFieldProperties))if(previous[key]!==field[key])
      rows.push({scope:'字段修改',field_name:field.field_name,property:label,before:previous[key],after:field[key]})
  }
  for(const field of before.fields)if(!current.has(field.field_name))rows.push({scope:'字段删除',field_name:field.field_name,property:'整个字段',before:field.data_type,after:null})
  return rows
}
export function historyValue(value){return value===null?'—':typeof value==='boolean'?(value?'是':'否'):value===''?'（空）':String(value)}
