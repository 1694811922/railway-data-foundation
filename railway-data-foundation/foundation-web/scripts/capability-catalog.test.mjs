import { test } from 'node:test'
import assert from 'node:assert/strict'
import { loadCapabilityCatalog,filterCapabilities,capabilitySaveScope } from '../src/utils/capabilityCatalog.js'

const resources=[{resource_id:'R1',resource_name:'港口事件资源',status:'PUBLISHED'},{resource_id:'R2',resource_name:'铁路调度资源',status:'DRAFT'}]
const rows=[{capability_id:'C1',capability_name:'事件查询',resource_id:'R1',type:'REST_API',status:'ENABLED',health_status:'UP',endpoint:'/containers/events'},
  {capability_id:'C2',capability_name:'变化主题',resource_id:'R2',type:'KAFKA_TOPIC',status:'DRAFT',health_status:'UNKNOWN',topic:'port.event.v1'}]
test('both capability rows and resource choices are read beyond the first page',async()=>{
  const requests=[]
  const fetchers=Object.fromEntries(['capabilities','resources'].map(key=>[key,async ({offset,limit})=>{
    requests.push({key,offset})
    return Array.from({length:451},(_,i)=>({[key==='resources'?'resource_id':'capability_id']:'ID_'+i})).slice(offset,offset+limit)
  }]))
  const result=await loadCapabilityCatalog(fetchers)
  assert.equal(result.demo,false)
  assert.equal(result.capabilities.rows.length,451)
  assert.equal(result.resources.rows.length,451)
  assert.ok(requests.some(r=>r.key==='resources' && r.offset===400))
})
test('failed regions clear their rows and never independently substitute demo metadata',async()=>{
  let demoCalls=0
  const result=await loadCapabilityCatalog({capabilities:async()=>rows,resources:async()=>{throw new Error('offline')}},{getDemoRows:()=>{demoCalls++;return []}})
  assert.equal(result.resources.error,true)
  assert.deepEqual(result.resources.rows,[])
  assert.equal(result.capabilities.error,false)
  assert.equal(result.capabilities.rows.length,2)
  assert.equal(demoCalls,0)
  const demo=await loadCapabilityCatalog({}, {demo:true,getDemoRows:key=>key==='resources'?resources:rows})
  assert.equal(demo.demo,true)
  assert.equal(demo.resources.rows.length,2)
})
test('capability search intersects type, resource, state and health filters',()=>{
  assert.equal(filterCapabilities(rows,{keyword:'CONTAINERS 港口'},resources)[0].capability_id,'C1')
  assert.equal(filterCapabilities(rows,{keyword:'port.event.v1',type:'KAFKA_TOPIC',status:'DRAFT',health_status:'UNKNOWN'},resources).length,1)
  assert.equal(filterCapabilities(rows,{resource_id:'R2',health_status:'UP'},resources).length,0)
  assert.equal(filterCapabilities(rows,{keyword:'C1',type:'KAFKA_TOPIC'},resources).length,0)
})
test('save rejects missing/unpublished resources and mixed real/demo scope',()=>{
  assert.equal(capabilitySaveScope({resource_id:'R1'},resources,false),'')
  assert.match(capabilitySaveScope({resource_id:'R2'},resources,false),/已发布/)
  assert.match(capabilitySaveScope({resource_id:'missing'},resources,false),/读取成功/)
  assert.match(capabilitySaveScope({resource_id:'R1'},resources,true),/不一致/)
  const demo=[{...resources[0],_demo:true}]
  assert.equal(capabilitySaveScope({resource_id:'R1'},demo,true),'')
  assert.match(capabilitySaveScope({resource_id:'R1'},demo,false),/不一致/)
})
