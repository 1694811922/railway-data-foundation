import test from 'node:test'
import assert from 'node:assert/strict'
import { existingRegisteredResource, registrationCreateScope, registrationEditScope, registrationQuery, verifyRegistrationOrigin } from '../src/utils/resourceRegistration.js'
import { validateResource,resourcePayload,isResourceConflict } from '../src/utils/resourceForm.js'
const query={task:'SCAN_A',source:'SRC_A',schema:'port_db',fromTable:'container_event',sourceName:'伪造名称'}
const scan={task_id:'SCAN_A',source_id:'SRC_A',source_name:'真实来源',status:'SUCCESS'}
const table={schema_name:'port_db',table_name:'container_event',field_count:12,estimated_rows:20}
const form={source_id:'SRC_A',scan_task_id:'SCAN_A',schema_name:'port_db',physical_table:'container_event',resource_type:'TABLE',resource_name:'事件数据',business_description:'业务描述',owner_department:'科研组',security_level:'内部',update_mode:'实时'}
const resource={...form,resource_id:'RES_A',version:1,status:'DRAFT'}
test('resource edit carries captured version while create omits it, and recognizes only resource conflicts',()=>{
  assert.equal('version' in resourcePayload({...form,version:99}),false)
  assert.equal(resourcePayload({...form,version:99},1).version,1)
  assert.equal(isResourceConflict({response:{status:409,data:{code:10009}}}),true)
  assert.equal(isResourceConflict({response:{status:400,data:{code:10009}}}),false)
  assert.equal(isResourceConflict({response:{status:409,data:{code:10002}}}),false)
  assert.equal(isResourceConflict({code:'ECONNABORTED'}),false)
})
test('registration entry rejects incomplete and array query parameters',()=>{
  assert.equal(registrationQuery(query).physical_table,'container_event')
  for(const change of [{task:''},{source:['SRC_A']},{schema:'x'.repeat(65)},{fromTable:null}])assert.throws(()=>registrationQuery({...query,...change}))
})
test('verified source comes from matching successful task and its exact candidate table',async()=>{
  const fetchers={task:async()=>({value:scan,demo:false}),tables:async()=>({rows:[table],demo:false})}
  const origin=await verifyRegistrationOrigin(query,fetchers)
  assert.equal(origin.source_name,'真实来源');assert.equal(origin.field_count,12)
  for(const changes of [{source_id:'SRC_B'},{task_id:'SCAN_B'},{status:'FAILED'}])
    await assert.rejects(verifyRegistrationOrigin(query,{...fetchers,task:async()=>({value:{...scan,...changes},demo:false})}))
  for(const response of [{rows:[table],demo:true},{rows:[],demo:false},{rows:[table,table],demo:false},{rows:null,demo:false}])
    await assert.rejects(verifyRegistrationOrigin(query,{...fetchers,tables:async()=>response}))
})
test('duplicate lookup matches source/schema/table and mode without conflating identical table names',()=>{
  const rows=[{...resource,source_id:'SRC_B'},{...resource,schema_name:'other_db'},{...resource,_demo:true},resource]
  assert.equal(existingRegisteredResource(rows,form,false),resource)
  assert.equal(existingRegisteredResource(rows,form,true),rows[2])
  assert.equal(existingRegisteredResource(rows,null,false),null)
})
test('save binds verified creation or current editing version and immutable origin',()=>{
  assert.equal(registrationCreateScope({...form,task_id:'SCAN_A',demo:false},form,false),'')
  assert.ok(registrationCreateScope(null,form,false))
  assert.ok(registrationCreateScope({...form,demo:true},form,false))
  assert.equal(registrationEditScope(resource,form,[resource],false),'')
  assert.ok(registrationEditScope(resource,form,[{...resource,version:2}],false))
  assert.ok(registrationEditScope(resource,{...form,physical_table:'train_dispatch'},[resource],false))
  assert.ok(registrationEditScope(resource,form,[{...resource,_demo:true}],false))
})

test('resource saves carry the reviewed scan and cannot switch snapshots on create or edit',()=>{
  assert.equal(resourcePayload(form).scan_task_id,'SCAN_A')
  const origin={...form,task_id:'SCAN_A',demo:false}
  assert.ok(registrationCreateScope(origin,{...form,scan_task_id:'SCAN_B'},false))
  assert.ok(registrationCreateScope(origin,{...form,scan_task_id:undefined},false))
  assert.ok(registrationEditScope(resource,{...form,scan_task_id:'SCAN_B'},[resource],false))
})
test('resource validation matches current server lengths and table type',()=>{
  assert.equal(validateResource(form),'')
  for(const [key,length] of [['resource_name',121],['schema_name',65],['physical_table',65],['business_description',2001],['owner_department',121]])
    assert.ok(validateResource({...form,[key]:'x'.repeat(length)}))
  assert.ok(validateResource({...form,resource_type:'TOPIC'}))
})
