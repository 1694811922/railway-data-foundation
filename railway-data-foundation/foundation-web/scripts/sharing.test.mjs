import test from 'node:test'
import assert from 'node:assert/strict'
import {actionsFor,assertActionResult,assertPolicy,assertRelationship,filterRelationships,sharingPayload,timeFields,validity} from '../src/utils/sharing.js'
const fields=[{field_name:'container_no',data_type:'varchar(20)',time_field:false},{field_name:'event_time',data_type:'datetime(3)',time_field:true},{field_name:'created_at',data_type:'timestamp',time_field:false}]
const now=Date.parse('2026-10-04T00:00:00Z')
const form=()=>({relationship_name:' 集装箱科研共享 ',provider_subject_id:'SUB_provider',demand_subject_id:'SUB_demand',resource_id:'RES_test',capability_id:'CAP_test',purpose:' 科研 ',reason:' 管理员登记 ',allowed_fields:['container_no','event_time'],time_field:'event_time',valid_range:['2026-10-01T00:00:00Z','2026-11-01T00:00:00Z'],data_range:['2026-01-01T00:00:00Z','2026-09-01T00:00:00Z'],version:4})
const policy=()=>({policy_id:'POL_'+'a'.repeat(32),resource_id:'RES_test',allowed_fields:['container_no','event_time'],time_field:'event_time',data_from:'2026-01-01T00:00:00Z',data_until:'2026-09-01T00:00:00Z',version:1})
const row=()=>({relationship_id:'REL_'+'b'.repeat(32),relationship_name:'集装箱科研共享',provider_subject_id:'SUB_provider',demand_subject_id:'SUB_demand',provider_name:'提供单位',demand_name:'需求单位',resource_id:'RES_test',capability_id:'CAP_test',resource_name:'事件资源',capability_name:'查询接口',status:'DRAFT',version:1,valid_from:'2026-10-01T00:00:00Z',valid_until:'2026-11-01T00:00:00Z',provider_review_mode:'LEGACY',provider_confirmed_at:null,confirmed_at:null,policy:policy()})
test('授权有效期与数据时间范围独立转换，编辑不提交不可变来源',()=>{
  const body=sharingPayload(form(),{fields,now});assert.equal(body.relationship_name,'集装箱科研共享');assert.notEqual(body.valid_from,body.policy.data_from)
  assert.equal(body.policy.data_until,'2026-09-01T00:00:00.000Z');assert.equal(body.version,undefined)
  const edit=sharingPayload({...form(),status:'ACTIVE',operator_name:'伪造'},{fields,now,editing:true})
  assert.equal(edit.version,4);for(const key of ['provider_subject_id','resource_id','capability_id','status','operator_name'])assert.equal(edit[key],undefined)
})
test('空范围、逆序范围、过期授权和无效时间被拒绝',()=>{
  for(const patch of [{valid_range:[]},{valid_range:['2026-11-01','2026-10-01']},{valid_range:['2025-01-01','2025-02-01']},{data_range:['bad','2026-09-01']},{data_range:['2026-09-01','2026-01-01']}])assert.throws(()=>sharingPayload({...form(),...patch},{fields,now}))
})
test('不能通过省略、通配符、重复或外部字段扩大允许范围',()=>{
  for(const allowed_fields of [[],['*'],['secret_field'],['event_time','event_time']])assert.throws(()=>sharingPayload({...form(),allowed_fields},{fields,now}),/允许字段/)
  assert.throws(()=>sharingPayload(form(),{fields:[],now}),/允许字段/)
  assert.throws(()=>sharingPayload({...form(),allowed_fields:['*']},{fields:[...fields,{field_name:'*',data_type:'varchar(20)'}],now}),/通配符/)
})
test('时间字段必须属于资源、人工确认且为时间类型',()=>{
  assert.deepEqual(timeFields(fields).map(row=>row.field_name),['event_time'])
  for(const time_field of ['created_at','container_no','missing'])assert.throws(()=>sharingPayload({...form(),time_field},{fields,now}),/时间字段/)
  assert.throws(()=>sharingPayload(form(),{fields:fields.map(row=>({...row,data_type:'varchar(20)'})),now}),/时间字段/)
})
test('用途、原因、双方主体和当前版本必填',()=>{
  for(const patch of [{purpose:' '},{reason:''},{provider_subject_id:''},{demand_subject_id:'SUB_provider'}])assert.throws(()=>sharingPayload({...form(),...patch},{fields,now}))
  assert.throws(()=>sharingPayload({...form(),version:undefined},{fields,now,editing:true}),/版本/)
})
test('策略响应必须真实且归属正确，不接受空字段策略',()=>{
  assert.equal(assertPolicy(policy(),'RES_test').version,1)
  for(const patch of [{_demo:true},{resource_id:'RES_other'},{allowed_fields:[]},{allowed_fields:['*']},{allowed_fields:['event_time','event_time']},{data_until:'2025-01-01'},{version:0}])assert.throws(()=>assertPolicy({...policy(),...patch},'RES_test'))
})
test('确认、启用、暂停状态必须包含确认记录',()=>{
  for(const status of ['CONFIRMED','ACTIVE','SUSPENDED'])assert.throws(()=>assertRelationship({...row(),status}),/确认记录/)
  assert.throws(()=>assertRelationship({...row(),_demo:true}));assert.throws(()=>assertRelationship(row(),{resource_id:'RES_other'}))
  assert.throws(()=>assertRelationship({...row(),policy:{...policy(),resource_id:'RES_other'}}))
})
test('状态响应不能把失败或另一关系的变更冒充成功',()=>{
  const current=row(),result={...current,status:'PENDING',version:2};assert.equal(assertActionResult(result,current,'submit').status,'PENDING')
  for(const patch of [{status:'ACTIVE'},{version:1},{capability_id:'CAP_other'},{policy:{...policy(),allowed_fields:['container_no','secret_field']}}])assert.throws(()=>assertActionResult({...result,...patch},current,'submit'))
  assert.throws(()=>assertActionResult(result,current,'enable'),/不允许/)
  assert.deepEqual(actionsFor({status:'REVOKED'}),[]);assert.deepEqual(actionsFor({status:'PENDING'}),['confirm','return','revoke'])
})
test('有效期边界采用含起点、不含终点',()=>{
  const value=row();assert.equal(validity(value,Date.parse(value.valid_from)),'有效期内');assert.equal(validity(value,Date.parse(value.valid_until)),'已到期');assert.equal(validity(value,Date.parse(value.valid_from)-1),'尚未开始')
})
test('中文检索和状态组合筛选保持独立',()=>{
  const a=row(),b={...row(),status:'REVOKED',relationship_name:'其他共享'}
  assert.equal(filterRelationships([a,b],{keyword:'科研',status:'DRAFT'}).length,1);assert.equal(filterRelationships([a,b],{keyword:'科研',status:'REVOKED'}).length,0)
  assert.equal(filterRelationships([a,b],{keyword:'需求单位'}).length,2)
})
