import test from 'node:test'
import assert from 'node:assert/strict'
import { capabilityActionReason, capabilityEditScope, capabilityPayload, capabilityRowScope, capabilityStateExplanation } from '../src/utils/capabilityWorkflow.js'
import { validateCapability } from '../src/utils/capabilityForm.js'
const rest={capability_id:'CAP_A',capability_name:'查询接口',resource_id:'RES_A',type:'REST_API',http_method:'GET',endpoint:'/containers/events',protocol:'HTTP',auth_type:'NONE',status:'DRAFT',health_status:'UNKNOWN',version:1}
test('REST lifecycle requires verification and UP; disabling remains explicit',()=>{
  assert.equal(capabilityActionReason(rest,'verify'),'')
  assert.ok(capabilityActionReason(rest,'enable'))
  assert.ok(capabilityActionReason({...rest,status:'VERIFIED',health_status:'DOWN'},'enable'))
  assert.equal(capabilityActionReason({...rest,status:'VERIFIED',health_status:'UP'},'enable'),'')
  assert.equal(capabilityActionReason({...rest,status:'DISABLED',health_status:'UP'},'verify'),'')
  assert.equal(capabilityActionReason({...rest,status:'DISABLED',health_status:'UP'},'enable'),'')
  assert.equal(capabilityActionReason({...rest,status:'ENABLED'},'disable'),'')
  assert.ok(capabilityActionReason({...rest,status:'ENABLED'},'verify'))
  assert.ok(capabilityActionReason({...rest,status:'RETIRED'},'enable'))
  assert.ok(capabilityActionReason(rest,'retire'))
})
test('type/adapter gates separate metadata registration from implemented runtime',()=>{
  assert.ok(capabilityActionReason({...rest,auth_type:'TOKEN'},'verify'))
  const kafka={...rest,type:'KAFKA_TOPIC',protocol:'KAFKA',auth_type:'NONE',status:'VERIFIED',health_status:'UP'}
  assert.equal(capabilityActionReason(kafka,'verify'),'')
  assert.equal(capabilityActionReason(kafka,'enable'),'')
  assert.equal(capabilityActionReason({...kafka,auth_type:'SASL'},'enable'),'')
  assert.ok(capabilityActionReason({...kafka,protocol:'HTTP'},'enable'))
  for(const type of ['CDC_STREAM','READONLY_QUERY'])assert.ok(capabilityActionReason({...kafka,type},'verify'))
  assert.equal(capabilityActionReason({...kafka,type:'CDC_STREAM'},'enable',true),'')
})
test('editing binds current version, mode, immutable resource/type and DRAFT',()=>{
  assert.equal(capabilityRowScope(rest,[rest],false),'')
  assert.equal(capabilityEditScope(rest,rest,[rest],false),'')
  assert.ok(capabilityRowScope(rest,[{...rest,version:2}],false))
  assert.ok(capabilityRowScope(rest,[],false))
  assert.ok(capabilityRowScope(rest,[{...rest,_demo:true}],false))
  assert.ok(capabilityEditScope(rest,{...rest,resource_id:'RES_B'},[rest],false))
  assert.ok(capabilityEditScope(rest,{...rest,type:'CDC_STREAM'},[rest],false))
  const verified={...rest,status:'VERIFIED'};assert.ok(capabilityEditScope(verified,verified,[verified],false))
})
test('save payload excludes status/runtime/secrets and validates real backend limits',()=>{
  const body=capabilityPayload({...rest,password:'not-a-credential',enabled:true})
  assert.equal(Object.keys(body).length,12)
  for(const key of ['version','status','health_status','password','enabled','capability_id'])assert.equal(key in body,false)
  assert.equal(validateCapability(rest),'')
  for(const changes of [{capability_name:'x'.repeat(121)},{description:'x'.repeat(1001)},{message_schema:'x'.repeat(10001)},{type:'UNKNOWN'},{http_method:'DELETE'},{protocol:'FTP'}])
    assert.ok(validateCapability({...rest,...changes}))
})
test('status help never describes validation or demo enable as real data access',()=>{
  assert.match(capabilityStateExplanation({...rest,status:'VERIFIED'}),/最近探测成功/)
  assert.match(capabilityStateExplanation({...rest,status:'DISABLED'}),/不会自动启用/)
  assert.match(capabilityStateExplanation({...rest,status:'ENABLED'},true),/不开放真实服务/)
  assert.match(capabilityStateExplanation({...rest,status:'RETIRED'}),/没有提供退役/)
})
