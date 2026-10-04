import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loadSourceCatalog,filterSources,validateSource,sourceSaveScope } from '../src/utils/sourceCatalog.js'

test('source catalog reads beyond 200 and never substitutes demo after a real failure',async()=>{
  const rows=Array.from({length:451},(_,i)=>({source_id:'S'+i})),calls=[]
  const result=await loadSourceCatalog(async({limit,offset})=>{calls.push(offset);return rows.slice(offset,offset+limit)})
  assert.equal(result.rows.length,451);assert.ok(calls.includes(400));assert.equal(result.complete,true)
  let mockCalls=0
  const failed=await loadSourceCatalog(async()=>{throw new Error('offline')},{getDemoRows:()=>{mockCalls++;return rows}})
  assert.equal(failed.error,true);assert.deepEqual(failed.rows,[]);assert.equal(mockCalls,0)
  const demo=await loadSourceCatalog(null,{demo:true,getDemoRows:()=>rows})
  assert.equal(demo.demo,true);assert.equal(demo.rows.length,451)
})
test('source filters intersect name/address/database keywords, type and state',()=>{
  const rows=[{source_id:'S1',source_name:'广州港业务',host:'127.0.0.1',port:13306,database_name:'port_db',status:'ENABLED',source_type:'MYSQL'},
    {source_id:'S2',source_name:'铁路调度',host:'dispatch.local',database_name:'dispatch_db',status:'DISABLED',source_type:'ORACLE'}]
  assert.equal(filterSources(rows,{keyword:'广州 PORT_DB 13306',source_type:'MYSQL',status:'ENABLED'})[0].source_id,'S1')
  assert.equal(filterSources(rows,{keyword:'dispatch.local',status:'ENABLED'}).length,0)
  assert.equal(filterSources(rows,{status:'DISABLED'}).length,1)
})
test('source form validates dynamic connection values and blank password edit semantics',()=>{
  const form={source_name:'港口',source_type:'MYSQL',host:'127.0.0.1',port:3306,database_name:'port_db',username:'reader',password:'test-only',ssl_mode:'PREFERRED',status:'ENABLED',connection_mode:'DYNAMIC'}
  assert.equal(validateSource(form),'')
  assert.match(validateSource({...form,password:''}),/密码/)
  assert.equal(validateSource({...form,password:''},'S1'),'')
  assert.match(validateSource({...form,password:'',connection_mode:'ENV_REFERENCE'},'S1'),/密码/)
  assert.match(validateSource({...form,host:'jdbc:mysql://127.0.0.1'}),/地址/)
  assert.match(validateSource({...form,port:65536}),/端口/)
  assert.match(validateSource({...form,database_name:'port_db;drop'}),/数据库名称/)
  assert.match(validateSource({...form,source_type:'ORACLE'}),/MySQL/)
})
test('editing rejects missing records and mixed real/demo form scope',()=>{
  const rows=[{source_id:'S1'},{source_id:'S2',_demo:true}]
  assert.equal(sourceSaveScope('',rows,false),'');assert.equal(sourceSaveScope('S1',rows,false),'')
  assert.match(sourceSaveScope('S2',rows,false),/不一致/);assert.match(sourceSaveScope('missing',rows,false),/读取列表/)
})
