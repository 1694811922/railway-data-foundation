import { test } from 'node:test'
import assert from 'node:assert/strict'
import { catalogQuery, catalogRouteQuery, filterResources, catalogPage, loadResourceCatalog } from '../src/utils/resourceCatalog.js'

const resources=[
  {resource_id:'RES_A',resource_name:'广州港事件',physical_table:'container_event',source_id:'S1',source_name:'港口库',owner_department:'数据部',status:'PUBLISHED',security_level:'内部'},
  {resource_id:'RES_B',resource_name:'铁路调度',physical_table:'train_dispatch',source_id:'S2',source_name:'铁路库',owner_department:'调度部',status:'DRAFT',security_level:'敏感'},
]
test('catalog search spans business and technical metadata, with intersecting filters',()=>{
  assert.equal(filterResources(resources,{keyword:'CONTAINER 数据部'})[0].resource_id,'RES_A')
  assert.equal(filterResources(resources,{keyword:'RES_B 铁路库'})[0].resource_id,'RES_B')
  assert.equal(filterResources(resources,{keyword:'事件',status:'DRAFT'}).length,0)
  assert.equal(filterResources(resources,{source_id:'S1',status:'PUBLISHED',security_level:'内部'}).length,1)
  assert.equal(filterResources(resources,{source_id:'S1',security_level:'敏感'}).length,0)
  assert.equal(filterResources(resources,{keyword:'   '}).length,2)
})
test('URL conditions reject invalid paging and preserve valid filters',()=>{
  const state=catalogQuery({q:'事件',sourceFilter:'S1',status:'PUBLISHED',security:'内部',page:'3',pageSize:'20'})
  assert.equal(state.page,3)
  assert.deepEqual(catalogQuery(catalogRouteQuery(state.filters,state.page,state.pageSize)),state)
  assert.equal(catalogQuery({page:'1e100',pageSize:'200',status:'INVALID',security:'INVALID'}).page,1)
  assert.equal(catalogQuery({page:'-1',pageSize:'200'}).pageSize,10)
  assert.equal(catalogQuery({q:['not','a','string']}).filters.keyword,'')
  assert.equal(catalogQuery({q:'x'.repeat(300)}).filters.keyword.length,200)
})
test('pagination clamps an out-of-range page after filters or refresh',()=>{
  const rows=Array.from({length:21},(_,id)=>({id}))
  assert.deepEqual(catalogPage(rows,3,10).rows,[{id:20}])
  assert.equal(catalogPage(rows,99,10).page,3)
  assert.equal(catalogPage([],99,10).page,1)
  assert.equal(catalogPage(rows,1,999).rows.length,10)
  assert.equal(catalogPage(rows,2,20).rows.length,1)
})
test('catalog fallback only occurs before a real response and only with explicit eligibility',async()=>{
  const options={getDemoRows:()=>resources,canUseDemo:()=>true}
  const offline=async()=>{throw new Error('offline')}
  assert.equal((await loadResourceCatalog(offline,options)).demo,true)
  await assert.rejects(loadResourceCatalog(offline,{...options,allowDemo:false}))
  let calls=0
  await assert.rejects(loadResourceCatalog(async()=>{
    if(calls++) throw new Error('second page failed')
    return Array.from({length:200},(_,i)=>({resource_id:'RES_'+i}))
  },options))
  await assert.rejects(loadResourceCatalog(async()=>({malformed:true}),options),/响应格式/)
  const empty=await loadResourceCatalog(async()=>[],options)
  assert.equal(empty.demo,false)
  assert.equal(empty.rows.length,0)
  assert.equal((await loadResourceCatalog(()=>{throw new Error('Must not contact API')},{...options,forceMock:true})).demo,true)
})
