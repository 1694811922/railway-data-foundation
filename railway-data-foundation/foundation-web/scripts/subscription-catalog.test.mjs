import test from 'node:test'
import assert from 'node:assert/strict'
import { subscriptionProviders, subscriptionDemands, subscriptionResources, subscriptionCapabilities, subscriptionSelectionError, subscriptionEntry } from '../src/utils/subscriptionCatalog.js'

const user = {role:'DEMAND',subject_id:'D1'}
const subjects = [
  {subject_id:'P1',subject_type:'PROVIDER',status:'ENABLED'}, {subject_id:'P2',subject_type:'PROVIDER',status:'ENABLED'},
  {subject_id:'D1',subject_type:'DEMAND',status:'ENABLED'}, {subject_id:'D2',subject_type:'DEMAND',status:'ENABLED'},
]
const resource = {resource_id:'R1',provider_subject_id:'P1',status:'PUBLISHED'}
const capability = {capability_id:'C1',resource_id:'R1',type:'KAFKA_TOPIC',auth_type:'SASL',status:'ENABLED',topic:'port.event.v1'}
const references = {subjects,resources:[resource,{...resource,resource_id:'R2',provider_subject_id:'P2'},{...resource,resource_id:'OLD',provider_subject_id:null}],capabilities:[capability]}
const form = {provider_subject_id:'P1',demand_subject_id:'D1',resource_id:'R1',capability_id:'C1'}

test('提供方和需求方选项剔除停用和演示数据，需求方只能选择本人主体',()=>{
  const rows=[...subjects,{...subjects[0],subject_id:'OFF',status:'DISABLED'},{...subjects[0],subject_id:'MOCK',_demo:true}]
  assert.deepEqual(subscriptionProviders(rows).map(r=>r.subject_id),['P1','P2'])
  assert.deepEqual(subscriptionDemands(rows,user).map(r=>r.subject_id),['D1'])
  assert.equal(subscriptionDemands(rows,{role:'ADMIN'}).length,2)
  assert.equal(subscriptionDemands(rows,{role:'PROVIDER',subject_id:'P1'}).length,0)
})
test('资源选项按提供方过滤，不让普通需求方接管无归属存量',()=>{
  assert.deepEqual(subscriptionResources(references.resources,'P1','DEMAND').map(r=>r.resource_id),['R1'])
  assert.deepEqual(subscriptionResources(references.resources,'P2','DEMAND').map(r=>r.resource_id),['R2'])
  assert.deepEqual(subscriptionResources(references.resources,'P1','ADMIN').map(r=>r.resource_id),['R1','OLD'])
  for(const role of ['PROVIDER',undefined])assert.deepEqual(subscriptionResources(references.resources,'P1',role),[])
  assert.deepEqual(subscriptionResources(references.resources,'','ADMIN'),[])
  assert.deepEqual(subscriptionResources([{...resource,status:'DRAFT'},{...resource,_demo:true}],'P1','ADMIN'),[])
})
test('能力限定资源、Kafka、SASL和精确主题，允许草稿登记但不伪造已授权',()=>{
  const rows=[capability,...[{resource_id:'R2'},{type:'REST_API'},{auth_type:'NONE'},{status:'RETIRED'},{topic:'*'},{topic:'..'},{_demo:true},{status:undefined}].map(p=>({...capability,...p,capability_id:JSON.stringify(p)}))]
  assert.deepEqual(subscriptionCapabilities(rows,'R1'),[capability])
  assert.equal(subscriptionCapabilities([{...capability,status:'DRAFT'}],'R1').length,1)
  assert.deepEqual(subscriptionCapabilities(rows,''),[])
})
test('提交前拒绝跨提供方资源、其他需求方身份和跨资源能力',()=>{
  assert.equal(subscriptionSelectionError(form,references,user),'')
  assert.match(subscriptionSelectionError({...form,provider_subject_id:'P2'},references,user),/不属于/)
  assert.match(subscriptionSelectionError({...form,demand_subject_id:'D2'},references,user),/身份/)
  assert.match(subscriptionSelectionError(form,{...references,capabilities:[{...capability,resource_id:'R2'}]},user),/该资源/)
  assert.match(subscriptionSelectionError(form,references,{role:'PROVIDER'}),/角色/)
})
test('目录申请入口精确带入提供方、本人需求方和对应能力，不取其他资源替代',()=>{
  assert.deepEqual(subscriptionEntry({resource:'R1',capability:'C1'},references,user),form)
  for(const query of [{resource:['R1'],capability:'C1'},{resource:'R1'}, {capability:'C1'}])assert.throws(()=>subscriptionEntry(query,references,user),/缺少/)
  for(const query of [{resource:'R2',capability:'C1'},{resource:'missing',capability:'C1'},{resource:'OLD',capability:'C1'}])assert.throws(()=>subscriptionEntry(query,references,user))
  assert.throws(()=>subscriptionEntry({resource:'R1',capability:'C1'},references,{role:'PROVIDER'}),/角色/)
})
