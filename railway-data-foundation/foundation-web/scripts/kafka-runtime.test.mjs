import test from 'node:test'
import assert from 'node:assert/strict'
import {supportedKafka,capabilityActionReason,capabilityStateExplanation,kafkaRuntimeMatches} from '../src/utils/capabilityWorkflow.js'
const kafka={type:'KAFKA_TOPIC',protocol:'KAFKA',auth_type:'SASL',status:'VERIFIED',health_status:'UP'}
test('Kafka send lifecycle supports independent SASL and local unauthenticated adapters',()=>{
 for(const auth_type of ['NONE','SASL']){
  const row={...kafka,auth_type};assert.equal(supportedKafka(row),true)
  assert.equal(capabilityActionReason(row,'verify'),'');assert.equal(capabilityActionReason(row,'enable'),'')
  assert.equal(capabilityActionReason({...row,status:'ENABLED'},'disable'),'')
 }
 for(const patch of [{protocol:'HTTP'},{auth_type:'TOKEN'},{type:'CDC_STREAM'}])assert.equal(supportedKafka({...kafka,...patch}),false)
})
test('Kafka unhealthy/draft/retired cannot enable; verification never replaces explicit enable',()=>{
 for(const patch of [{status:'DRAFT'},{status:'RETIRED'},{health_status:'DOWN'},{health_status:'UNKNOWN'}])assert.ok(capabilityActionReason({...kafka,...patch},'enable'))
 assert.equal(capabilityActionReason({...kafka,status:'DISABLED'},'verify'),'')
 assert.ok(capabilityActionReason({...kafka,status:'ENABLED'},'verify'))
})
test('Kafka state help differentiates production admission from subscriber rights and delivered messages',()=>{
 assert.match(capabilityStateExplanation({...kafka,status:'ENABLED'}),/订阅授权尚未接入/)
 assert.match(capabilityStateExplanation({...kafka,status:'DISABLED'}),/不撤销订阅权限或删除已有消息/)
 assert.match(capabilityStateExplanation({...kafka,status:'ENABLED'},true),/不开放真实服务/)
})
test('Kafka runtime reads must match the capability, topic, authentication and response types',()=>{
 const row={...kafka,capability_id:'CAP_A',topic:'topic.a'},state={capability_id:'CAP_A',topic:'topic.a',auth_type:'SASL',type:'KAFKA_TOPIC',enabled:false,reason:'未启用'}
 assert.equal(kafkaRuntimeMatches(row,state),true)
 for(const patch of [{capability_id:'CAP_B'},{type:'REST_API'},{topic:'topic.b'},{auth_type:'NONE'},{enabled:'true'},{reason:''}])assert.equal(kafkaRuntimeMatches(row,{...state,...patch}),false)
 assert.equal(kafkaRuntimeMatches(row,null),false)
})
