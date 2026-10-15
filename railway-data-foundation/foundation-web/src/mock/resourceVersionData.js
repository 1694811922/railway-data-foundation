// Explicit demo snapshots only. No fabricated versions before the initial baseline.
export function demoVersionSnapshot(resource,fields,action='BASELINE',changed_field=null,recorded_at=resource.updated_at){
  const {_demo,...saved}=resource
  return {entry:{resource_id:resource.resource_id,version:resource.version,action,changed_field,recorded_at},resource:structuredClone(saved),
    fields:fields.map(field=>Object.fromEntries(['field_name','data_type','primary_key','technical_comment','ordinal_position','business_name','business_description','time_field','sensitivity_level','resource_version'].map(key=>[key,
      key==='resource_version'?resource.version:key==='ordinal_position'?(field[key]??fields.indexOf(field)+1):field[key]??(['primary_key','time_field'].includes(key)?false:'')])))}
}
export function demoVersionList(state,id,{limit=20,offset=0}={}){
  const snapshots=state.resource_versions[id] || []
  return {items:[...snapshots].sort((a,b)=>b.entry.version-a.entry.version).slice(offset,offset+limit).map(row=>structuredClone(row.entry)),total:snapshots.length}
}
export function demoVersionDetail(state,id,version){
  const snapshots=state.resource_versions[id] || [],snapshot=snapshots.find(row=>row.entry.version===version)
  if(!snapshot)throw new Error('该演示版本未留存，不能还原历史状态')
  return structuredClone({snapshot,previous_snapshot:snapshots.find(row=>row.entry.version===version-1) || null})
}
