import test from 'node:test'
import assert from 'node:assert/strict'
import {assertSubscription,assertSubscriptionAction,assertSubscriptionPage,assertSubscriptionDelivery,subscriptionExecutionActionsFor,subscriptionActionsFor,subscriptionPayload,subscriptionReadGate} from '../src/utils/subscription.js'

const suffix='a'.repeat(32)
const grant=()=>({grant_id:'KSG_'+suffix,grant_name:'科研事件订阅',provider_subject_id:'SUB_p',demand_subject_id:'SUB_d',resource_id:'RES_r',capability_id:'CAP_c',topic:'port.container.event.v1',auth_type:'SASL',consumer_group:'rdf.subscription.'+suffix,scope:'FULL_TOPIC',purpose:'科研',status:'DRAFT',version:1,valid_from:'2026-01-01T00:00:00Z',valid_until:'2099-01-01T00:00:00Z',provider_review_mode:'LEGACY',provider_confirmed_at:null,confirmed_at:null,execution_state:'NOT_PROVISIONED',broker_access_confirmed:false})
const form=()=>({...grant(),reason:'登记整主题',valid_range:[new Date(Date.now()-60000),new Date(Date.now()+3600000)],whole_topic_acknowledged:true})
test('整主题申请必须明确接受范围，不能夹带REST策略或Broker权限',()=>{
  const body=subscriptionPayload({...form(),policy:{allowed_fields:['id']},consumer_group:'*',password:'secret'})
  assert.equal(body.whole_topic_acknowledged,true);assert.equal(body.policy,undefined);assert.equal(body.consumer_group,undefined);assert.equal(body.password,undefined)
  assert.throws(()=>subscriptionPayload({...form(),whole_topic_acknowledged:false}),/完整消息/)
  assert.throws(()=>subscriptionPayload({...form(),demand_subject_id:'SUB_p'}),/不同/)
  assert.throws(()=>subscriptionPayload({...form(),valid_range:[]}),/有效期/)
  assert.throws(()=>subscriptionPayload({...form(),valid_range:[null,new Date(Date.now()+3600000)]}),/有效期/)
  assert.throws(()=>subscriptionPayload({...form(),reason:' '}),/操作原因/)
})
test('草稿编辑仅发送可变信息和原版本',()=>{
  const body=subscriptionPayload(form(),true)
  assert.equal(body.version,1);for(const field of ['resource_id','capability_id','provider_subject_id','consumer_group','scope'])assert.equal(body[field],undefined)
  assert.throws(()=>subscriptionPayload({...form(),version:undefined},true),/版本/)
})
test('真实响应拒绝mock、错误范围、错误主题及伪造已生效',()=>{
  assert.equal(assertSubscription(grant()).grant_id,'KSG_'+suffix)
  for(const patch of [{_demo:true},{scope:'FIELD_FILTERED'},{execution_state:'ACTIVE'},{broker_access_confirmed:true},{consumer_group:'other'},{topic:'*'},{auth_type:'NONE'},{policy:{}},{password:'secret'},{version:0}])assert.throws(()=>assertSubscription({...grant(),...patch}))
  assert.throws(()=>assertSubscription({...grant(),status:'APPROVED'}),/确认时间/)
})
test('审批状态机无启用入口，动作响应必须属于当前申请与版本',()=>{
  assert.deepEqual(subscriptionActionsFor(grant()),['submit','revoke']);assert.deepEqual(subscriptionActionsFor({...grant(),status:'REVOKED'}),[])
  const current=grant(),result={...grant(),status:'PENDING',version:2}
  assert.equal(assertSubscriptionAction(result,current,'submit').status,'PENDING')
  for(const patch of [{version:1},{topic:'other'},{capability_id:'CAP_wrong'},{status:'APPROVED'}])assert.throws(()=>assertSubscriptionAction({...result,...patch},current,'submit'))
  assert.throws(()=>assertSubscriptionAction(result,current,'enable'),/不允许/)
})
test('服务端分页响应校验覆盖空页、总数和重复项',()=>{
  assert.equal(assertSubscriptionPage({items:[grant()],total:21},{limit:20,offset:20}).total,21)
  assert.deepEqual(assertSubscriptionPage({items:[],total:0}).items,[])
  assert.throws(()=>assertSubscriptionPage({items:[grant()],total:0}))
  assert.throws(()=>assertSubscriptionPage({items:[grant(),grant()],total:2}),/重复/)
  assert.throws(()=>assertSubscriptionPage({items:[grant()],total:1},{limit:0}))
})
test('重试、切换页和离开页面使旧响应失效，各读取区域相互独立',async()=>{
  const list=subscriptionReadGate(),detail=subscriptionReadGate(),first=list.begin(),selected=detail.begin()
  let resolve;const pending=new Promise(done=>{resolve=done});let shown=''
  const applying=pending.then(value=>{if(list.current(first))shown=value})
  const retry=list.begin();assert.equal(list.current(first),false);assert.equal(detail.current(selected),true)
  resolve('旧页');await applying;assert.equal(shown,'');assert.equal(list.current(retry),true)
  detail.invalidate();assert.equal(detail.current(selected),false)
  list.dispose();assert.equal(list.current(retry),false);assert.equal(list.current(list.begin()),false)
})

const approved=()=>({...grant(),status:'APPROVED',confirmed_at:'2026-01-01T00:00:00Z',execution_enabled:true})
const active=()=>({...approved(),execution_state:'ACTIVE',broker_access_confirmed:true,broker_username:'rdfk_'+suffix,credential_available:true})
test('审批通过与权限配置分别操作，失败仅重试处理，不可领取密码',()=>{
  assert.deepEqual(subscriptionExecutionActionsFor(approved()),['provision'])
  assert.deepEqual(subscriptionExecutionActionsFor({...approved(),execution_enabled:false}),[])
  assert.deepEqual(subscriptionExecutionActionsFor(active()),['stop','credential'])
  assert.deepEqual(subscriptionExecutionActionsFor({...active(),credential_available:false}),['stop'])
  assert.deepEqual(subscriptionExecutionActionsFor({...active(),execution_state:'FAILED',broker_access_confirmed:false,credential_available:false}),['stop','retry'])
  assert.throws(()=>assertSubscription({...active(),execution_state:'FAILED'}),/实际权限/)
  assert.throws(()=>assertSubscription({...active(),broker_access_confirmed:false}),/领取凭据/)
})
test('一次性凭据必须匹配当前独立身份、主题、消费组和领取后版本',()=>{
  const current=active(),delivery={grant:{...current,version:2,credential_available:false},bootstrap_servers:'127.0.0.1:9092',security_protocol:'SASL_PLAINTEXT',sasl_mechanism:'SCRAM-SHA-512',username:current.broker_username,password:'ks_'+'a'.repeat(43),consumption_token:'rkc_'+'b'.repeat(43),topic:current.topic,consumer_group:current.consumer_group}
  assert.equal(assertSubscriptionDelivery(delivery,current),delivery)
  for(const patch of [{_demo:true},{consumption_token:undefined},{consumption_token:'ks_'+'a'.repeat(43)},{username:'admin'},{topic:'another'},{consumer_group:'*'},{sasl_mechanism:'PLAIN'},{security_protocol:'PLAINTEXT'},{grant:{...delivery.grant,version:1}},{grant:{...delivery.grant,credential_available:true}}])assert.throws(()=>assertSubscriptionDelivery({...delivery,...patch},current))
})
