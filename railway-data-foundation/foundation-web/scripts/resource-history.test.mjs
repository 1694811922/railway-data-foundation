import test from 'node:test'
import assert from 'node:assert/strict'
import {historyList,historyDetail,historyChanges,historyValue} from '../src/utils/resourceHistory.js'
import {demoStore} from '../src/mock/demoData.js'
const resource={resource_id:'RES_A',source_id:'SRC_A',scan_task_id:'SCAN_A',schema_name:'db',physical_table:'events',version:3,resource_name:'事件资源',source_name:'来源',business_description:'说明',owner_department:'科研组',security_level:'内部',update_mode:'实时',status:'DRAFT'}
const field={field_name:'time',data_type:'datetime(3)',primary_key:false,ordinal_position:1,technical_comment:'时间',business_name:'',business_description:'',time_field:false,sensitivity_level:''}
const snapshot=(version,action='FIELD_UPDATE')=>({entry:{resource_id:'RES_A',version,action,changed_field:action==='FIELD_UPDATE'?'time':null,recorded_at:'2026-10-09T01:00:00Z'},resource:{...resource,version},fields:[{...field,resource_version:version}]})
const wrap=(value,demo=false)=>({value,demo})
test('history lists require owned descending distinct versions and matching data mode',()=>{
  const rows=[snapshot(3).entry,snapshot(2).entry]
  assert.equal(historyList(wrap({items:rows,total:23}),'RES_A',false).total,23)
  for(const value of [{items:[...rows].reverse(),total:2},{items:[rows[0],rows[0]],total:2},{items:[{...rows[0],resource_id:'other'}],total:1},
    {items:[{...rows[0],action:'RESTORE'}],total:1},{items:[],total:-1}])assert.throws(()=>historyList(wrap(value),'RES_A',false))
  assert.throws(()=>historyList(wrap({items:rows,total:2},true),'RES_A',false))
})
test('history snapshots bind version source table and preceding version',()=>{
  const value={snapshot:snapshot(3),previous_snapshot:snapshot(2)}
  historyDetail(wrap(value),resource,3,false)
  for(const patch of [{version:4},{source_id:'other'},{physical_table:'other'},{scan_task_id:'other'}]){
    const invalid=structuredClone(value);Object.assign(invalid.snapshot.resource,patch);assert.throws(()=>historyDetail(wrap(invalid),resource,3,false))
  }
  assert.throws(()=>historyDetail(wrap({...value,previous_snapshot:snapshot(1)}),resource,3,false))
  assert.throws(()=>historyDetail(wrap(value,true),resource,3,false))
  const malformed=structuredClone(value);malformed.snapshot.fields[0].resource_version=2
  assert.throws(()=>historyDetail(wrap(malformed),resource,3,false))
})
test('missing previous snapshot means unknown changes, not unchanged',()=>{
  const value={snapshot:snapshot(3,'BASELINE'),previous_snapshot:null}
  historyDetail(wrap(value),resource,3,false);assert.equal(historyChanges(value),null)
  assert.deepEqual(historyChanges({snapshot:snapshot(3),previous_snapshot:snapshot(2)}),[])
})
test('changes include business fields and status but omit version and timestamps',()=>{
  const value={snapshot:snapshot(3),previous_snapshot:snapshot(2)}
  value.snapshot.resource.status='PUBLISHED';value.snapshot.fields[0].business_name='发生时间';value.snapshot.fields[0].time_field=true
  const rows=historyChanges(value);assert.equal(rows.length,3)
  assert.deepEqual(rows.map(row=>row.property),['资源状态','业务名称','时间字段'])
  assert.equal(historyValue(false),'否');assert.equal(historyValue(null),'—');assert.equal(historyValue(''),'（空）')
})
test('demo baseline and field versions preserve old snapshots and reject stale updates',()=>{
  const values=new Map();globalThis.localStorage={getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value)}
  const row=demoStore.list('resources')[0],before=JSON.stringify([...values])
  const first=demoStore.resourceVersion(row.resource_id,1);assert.equal(first.snapshot.entry.action,'BASELINE');assert.equal(first.previous_snapshot,null)
  assert.equal(JSON.stringify([...values]),before)
  demoStore.saveResourceField(row.resource_id,'event_time',{resource_version:1,business_name:'新的业务名',business_description:'说明',time_field:true,sensitivity_level:'内部'})
  const current=demoStore.list('resources')[0],second=demoStore.resourceVersion(row.resource_id,2)
  historyDetail(wrap(second,true),current,2,true)
  assert.deepEqual(demoStore.resourceVersion(row.resource_id,1),first)
  assert.equal(second.snapshot.entry.action,'FIELD_UPDATE');assert.equal(second.previous_snapshot.entry.version,1)
  assert.throws(()=>demoStore.saveResourceField(row.resource_id,'event_time',{resource_version:1}),/已被修改/)
  assert.equal(demoStore.resourceVersions(row.resource_id).total,2)
  assert.throws(()=>demoStore.resourceVersion(row.resource_id,99),/未留存/)
  delete globalThis.localStorage
})
test('legacy demo keeps only its available current baseline without guessing earlier versions',()=>{
  const values=new Map();globalThis.localStorage={getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value)}
  const row={...demoStore.list('resources')[0],version:7};demoStore.replace('resources',[row])
  // Simulate a saved pre-history browser store, rather than creating fictitious intermediate snapshots.
  for(const [key,text] of values){const state=JSON.parse(text);delete state.resource_versions;values.set(key,JSON.stringify(state))}
  assert.deepEqual(demoStore.resourceVersions(row.resource_id).items.map(entry=>entry.version),[7])
  assert.equal(demoStore.resourceVersion(row.resource_id,7).previous_snapshot,null)
  assert.throws(()=>demoStore.resourceVersion(row.resource_id,6),/未留存/)
  delete globalThis.localStorage
})
