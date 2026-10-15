import test from 'node:test'
import assert from 'node:assert/strict'
import { subjectPayload, assertSubject, filterSubjects } from '../src/utils/subject.js'
import { subjectDemoApi } from '../src/mock/subjectData.js'
const base={subject_id:'SUB_'+'a'.repeat(32),subject_code:'PORT_PROVIDER',subject_name:'港口提供方',subject_type:'PROVIDER',owner_department:'港口单位',description:'说明',status:'ENABLED',version:1}
test('编辑载荷不能修改身份、角色、状态或携带外来凭据',()=>{
  const result=subjectPayload({...base,subject_name:' 新名称 ',password:'SECRET',status:'DISABLED'},true)
  assert.deepEqual(result,{subject_name:'新名称',owner_department:'港口单位',description:'说明',version:1})
  assert.equal(Object.hasOwn(subjectPayload(base),'version'),false)
})
test('无效编码、角色、必填、超长文本及版本阻止提交',()=>{
  for(const patch of [{subject_code:'port'},{subject_code:'1AB'},{subject_type:'ADMIN'},{owner_department:' '},{subject_name:'x'.repeat(121)},{description:'x'.repeat(1001)}])
    assert.throws(()=>subjectPayload({...base,...patch}))
  for(const version of [undefined,0,'1',1.5])assert.throws(()=>subjectPayload({...base,version},true))
})
test('响应须匹配真实/演示模式和当前主体、版本与状态',()=>{
  assertSubject(base,false,{subject_id:base.subject_id,version:1})
  for(const patch of [{_demo:true},{subject_id:'SUB_FAKE'},{version:0},{status:'RETIRED'},{subject_type:'ADMIN'},{owner_department:''}])assert.throws(()=>assertSubject({...base,...patch}))
  for(const expected of [{subject_id:'SUB_'+'b'.repeat(32)},{version:2},{status:'DISABLED'},{subject_type:'DEMAND'}])assert.throws(()=>assertSubject(base,false,expected))
})
test('中文主体检索可与角色、状态组合，不混淆提供方与需求方',()=>{
  const demand={...base,subject_id:'SUB_'+'b'.repeat(32),subject_type:'DEMAND',subject_name:'铁路需求方',owner_department:'铁路单位',subject_code:'RAIL_DEMAND',status:'DISABLED'}
  assert.deepEqual(filterSubjects([base,demand],{keyword:'rail',type:'DEMAND',status:'DISABLED'}),[demand])
  assert.equal(filterSubjects([base,demand],{keyword:'港口',type:'DEMAND'}).length,0)
  assert.equal(filterSubjects([base,demand],{keyword:'单位'}).length,2)
})
test('显式演示主体独立持久保存，拒绝重复编码和过期版本',async()=>{
  const storage=new Map();globalThis.localStorage={getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)}
  const seed=await subjectDemoApi.list();assert.equal(seed.length,2);assert.ok(seed.every(row=>row._demo))
  const created=await subjectDemoApi.create(subjectPayload({...base,subject_code:'TEST_SUBJECT'}));assertSubject(created,true)
  await assert.rejects(subjectDemoApi.create(subjectPayload({...base,subject_code:'TEST_SUBJECT'})))
  const disabled=await subjectDemoApi.status(created.subject_id,{version:1,status:'DISABLED'})
  assert.equal(disabled.version,2)
  await assert.rejects(subjectDemoApi.update(created.subject_id,subjectPayload(created,true)))
  assert.equal((await subjectDemoApi.list()).find(row=>row.subject_id===created.subject_id).status,'DISABLED')
  delete globalThis.localStorage
})
