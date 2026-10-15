// Explicit, display-only comparison fixtures. Never added to real or demo scan catalogs.
import { demoStore,demoSeed } from './demoData.js'
import { parseUtcTime } from '../utils/time.js'
const baselineId='SCAN_DEMO_001',targetId='SCAN_DEMO_DIFF_001'
const field=(field_name,data_type,column_type,ordinal_position,extra={})=>({field_name,data_type,column_type,
  nullable:true,primary_key:false,default_value:null,technical_comment:'演示技术字段',ordinal_position,...extra})
const before=demoSeed.fields.map((row,index)=>field(row.field_name,row.data_type.split('(')[0],row.data_type,index+1,
  {nullable:row.nullable,primary_key:row.primary_key,technical_comment:row.technical_comment}))
const removed=before.find(row=>row.field_name==='equipment_no'),old=before.find(row=>row.field_name==='container_no')
const added=field('operation_code','varchar','varchar(30)',removed.ordinal_position)
const modified={...old,column_type:'varchar(40)'}
export const demoSchemaSnapshots={
  baseline:{table_comment:'广州港集装箱作业事件',fields:before},
  target:{table_comment:'广州港集装箱作业事件',fields:before.map(row=>row===removed?added:row===old?modified:{...row})},
}
function context(id) {
  const resource=demoStore.list('resources').find(row=>row.resource_id===id)
  if(!resource || resource.scan_task_id!==baselineId || resource.physical_table!=='container_event')
    throw new Error('该演示资源没有独立比较样例，暂不可比较')
  const scan=(task_id,started_at)=>({task_id,source_id:resource.source_id,status:'SUCCESS',started_at,finished_at:started_at})
  const original=demoStore.list('tasks').find(row=>row.task_id===baselineId)
  if(!original || !parseUtcTime(original.started_at))throw new Error('演示登记扫描不可确认，暂不可比较')
  return {resource,baseline:scan(baselineId,parseUtcTime(original.started_at).toISOString()),
    target:scan(targetId,new Date(parseUtcTime(original.started_at).getTime()+60000).toISOString())}
}
export function demoComparisonCandidates(id,{limit=20,offset=0}={}) {
  const {target}=context(id)
  return {items:[target].slice(offset,offset+limit),total:1}
}
export function demoComparison(id,scanTaskId) {
  const {resource,baseline,target}=context(id)
  if(scanTaskId!==targetId)throw new Error('演示对比扫描不存在')
  return {resource_id:id,resource_version:resource.version,source_id:resource.source_id,schema_name:resource.schema_name,
    physical_table:resource.physical_table,baseline_scan:baseline,target_scan:target,comparison_status:'CHANGED',
    summary:{added:1,removed:1,modified:1,unchanged:10},table_changes:[],changes:[
      {change_type:'ADDED',field_name:'operation_code',before:null,after:added,changed_properties:[]},
      {change_type:'REMOVED',field_name:'equipment_no',before:removed,after:null,changed_properties:[]},
      {change_type:'MODIFIED',field_name:'container_no',before:old,after:modified,changed_properties:['column_type']},
    ]}
}
