import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loadScanCatalog,filterScanTasks,filterMetadataRows,scanSourceError } from '../src/utils/scanCatalog.js'

test('scan tasks and selectable sources load beyond default API pages',async()=>{
  const calls=[]
  const fetchers=Object.fromEntries(['tasks','sources'].map(key=>[key,async({offset,limit})=>{
    calls.push({key,offset});return Array.from({length:451},(_,i)=>({[key==='tasks'?'task_id':'source_id']:'ID_'+i})).slice(offset,offset+limit)
  }]))
  const result=await loadScanCatalog(fetchers)
  assert.equal(result.tasks.rows.length,451);assert.equal(result.sources.rows.length,451)
  assert.ok(calls.some(r=>r.key==='tasks'&&r.offset===400));assert.ok(result.tasks.complete)
})
test('scan read errors isolate regions without borrowing demo tasks or sources',async()=>{
  let demoCalls=0
  const result=await loadScanCatalog({tasks:async()=>[{task_id:'T'}],sources:async()=>{throw new Error('offline')}},
    {getDemoRows:()=>{demoCalls++;return []}})
  assert.equal(result.tasks.error,false);assert.equal(result.sources.error,true)
  assert.deepEqual(result.sources.rows,[]);assert.equal(demoCalls,0)
  const demo=await loadScanCatalog({}, {demo:true,getDemoRows:key=>key==='tasks'?[{task_id:'DEMO'}]:[]})
  assert.equal(demo.demo,true);assert.equal(demo.tasks.rows.length,1)
})
test('task and technical metadata search combines keywords and conditions',()=>{
  const rows=[{task_id:'T1',source_id:'S1',source_name:'港口事件',status:'SUCCESS'},
    {task_id:'T2',source_id:'S2',source_name:'铁路调度',status:'FAILED',failure_reason:'权限不足'}]
  assert.equal(filterScanTasks(rows,{keyword:'T1 港口',source_id:'S1',status:'SUCCESS'}).length,1)
  assert.equal(filterScanTasks(rows,{keyword:'权限',status:'SUCCESS'}).length,0)
  assert.equal(filterScanTasks(rows,{keyword:'铁路 权限'}).length,1)
  const fields=[{field_name:'event_time',data_type:'TIMESTAMP',technical_comment:'作业发生时间'}, {field_name:'id',data_type:'BIGINT'}]
  assert.equal(filterMetadataRows(fields,'Time 作业',['field_name','data_type','technical_comment'])[0].field_name,'event_time')
  assert.equal(filterMetadataRows(fields,'', ['field_name']).length,2)
})
test('scan request rejects missing, disabled or mixed-mode sources',()=>{
  assert.match(scanSourceError(null,false),/请选择/)
  assert.match(scanSourceError({status:'DISABLED'},false),/停用/)
  assert.match(scanSourceError({status:'ENABLED',_demo:true},false),/不一致/)
  assert.equal(scanSourceError({status:'ENABLED'},false),'')
  assert.equal(scanSourceError({status:'ENABLED',_demo:true},true),'')
})
