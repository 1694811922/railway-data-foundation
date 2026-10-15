import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loadScanCatalog,filterScanTasks,filterMetadataRows,scanSourceError,scanResultRows,scanTableError,scanResponseTask } from '../src/utils/scanCatalog.js'
import { catalogPage } from '../src/utils/resourceCatalog.js'

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

test('scan tables require unique schema/name pairs and matching ownership/mode',()=>{
  const task={task_id:'T',source_id:'S',status:'SUCCESS'}
  const table={task_id:'T',schema_name:'port',table_name:'event'}
  assert.deepEqual(scanResultRows({demo:false,rows:[table]},task,false),[table])
  assert.equal(scanResultRows({demo:false,rows:[table,{...table,schema_name:'other'}]},task,false).length,2)
  for(const rows of [null,{},[null],[{...table,task_id:'foreign'}],[{...table,source_id:'foreign'}],
    [{...table,_demo:true}],[{...table,schema_name:''}],[table,{...table}]])
    assert.throws(()=>scanResultRows({demo:false,rows},task,false))
  assert.throws(()=>scanResultRows({demo:true,rows:[table]},task,false))
  assert.throws(()=>scanResultRows({demo:false,rows:[table]},{...task,status:'RUNNING'},false))
})

test('scan fields accept existing API shape but reject duplicate/foreign explicit ownership',()=>{
  const task={task_id:'T',source_id:'S',status:'SUCCESS'}, table={schema_name:'port',table_name:'event'}
  const field={field_name:'id',data_type:'bigint'}
  assert.deepEqual(scanResultRows({demo:false,rows:[field]},task,false,table),[field])
  for(const rows of [[field,field],[{...field,task_id:'other'}],[{...field,table_name:'other'}],
    [{...field,schema_name:'other'}],[{...field,field_name:''}],[{...field,_demo:true}]])
    assert.throws(()=>scanResultRows({demo:false,rows},task,false,table))
  assert.deepEqual(scanResultRows({demo:true,rows:[{...field,_demo:true}]},{...task,_demo:true},true,table),[{...field,_demo:true}])
})

test('only a table actually loaded for the current successful task can be used',()=>{
  const task={task_id:'T',status:'SUCCESS'}, table={schema_name:'p',table_name:'event'}
  assert.equal(scanTableError(table,task,[table],false),'')
  assert.ok(scanTableError({...table},task,[table],false))
  assert.ok(scanTableError(table,{...task,status:'FAILED'},[table],false))
  assert.ok(scanTableError(table,{...task,_demo:true},[table],false))
})

test('scan write response must identify the requested source and mode before automatic reload',()=>{
  const source={source_id:'S'}, task={task_id:'T',source_id:'S',status:'SUCCESS'}
  for(const status of ['SUCCESS','FAILED','RUNNING','PENDING'])
    assert.equal(scanResponseTask({value:{...task,status},demo:false},source,false).status,status)
  for(const value of [null,{...task,task_id:''},{...task,source_id:'other'},{...task,status:'unknown'},{...task,_demo:true}])
    assert.throws(()=>scanResponseTask({value,demo:false},source,false),/刷新任务/)
  assert.throws(()=>scanResponseTask({value:task,demo:true},source,false))
})

test('large scan tables/fields paginate and clamp after metadata filtering',()=>{
  const fields=Array.from({length:125},(_,i)=>({field_name:'field_'+i,technical_comment:i===124?'目标':''}))
  assert.equal(catalogPage(fields,13,10).rows[0].field_name,'field_120')
  assert.equal(catalogPage(fields,13,10).rows.length,5)
  const found=filterMetadataRows(fields,'目标',['field_name','technical_comment'])
  assert.equal(catalogPage(found,13,10).page,1)
  assert.equal(catalogPage(found,13,10).rows[0].field_name,'field_124')
  assert.equal(catalogPage(fields,1,50).rows.length,50)
})
