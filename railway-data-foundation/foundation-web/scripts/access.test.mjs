import {test} from 'node:test'
import assert from 'node:assert/strict'
import {credentialPayload,assertCredential,assertIssued,credentialEvents,callerToken,invocationParams,providerRows} from '../src/utils/access.js'
const now=Date.parse('2026-10-04T12:00:00Z'),subject='SUB_'+'a'.repeat(32),token='rdf_'+'A'.repeat(43)
const credential={credential_id:'AUTH_'+'b'.repeat(32),subject_id:subject,credential_name:'科研调用',status:'ENABLED',version:1,expires_at:'2026-11-04T12:00:00Z'}
test('credential issue requires a demand selection, future expiry and reason; no token in payload',()=>{
  const form={subject_id:subject,credential_name:' 科研调用 ',reason:' 组会验证 ',expires_at:credential.expires_at}
  assert.deepEqual(credentialPayload(form,now),{credential_name:'科研调用',reason:'组会验证',expires_at:'2026-11-04T12:00:00.000Z'})
  for(const patch of [{subject_id:''},{credential_name:''},{reason:''},{expires_at:'invalid'},{expires_at:new Date(now)},{credential_name:'a'.repeat(121)}])assert.throws(()=>credentialPayload({...form,...patch},now))
})
test('metadata rejects secrets, foreign scope, mocks, broken states and conflicting versions',()=>{
  assert.equal(assertCredential(credential,{subject_id:subject,version:1}),credential)
  for(const patch of [{token},{token_digest:'digest'},{password:'secret'},{subject_id:'bad'},{_demo:true},{status:'DRAFT'},{version:0},{expires_at:'invalid'},{status:'REVOKED'}])assert.throws(()=>assertCredential({...credential,...patch}))
  assert.throws(()=>assertCredential(credential,{subject_id:'SUB_'+'c'.repeat(32)}))
  assert.throws(()=>assertCredential(credential,{version:2}))
})
test('one-time signing response must match subject, name and actual expiry',()=>{
  const body={credential_name:credential.credential_name,expires_at:credential.expires_at},issued={credential,token}
  assert.equal(assertIssued(issued,subject,body),issued)
  for(const patch of [{token:''},{token:'SUB_'+'a'.repeat(32)},{credential:{...credential,credential_name:'其他'}},{credential:{...credential,expires_at:'2026-11-05T12:00:00Z'}}])assert.throws(()=>assertIssued({...issued,...patch},subject,body))
})
test('self-declared subject and database credentials cannot substitute for random caller token',()=>{
  assert.equal(callerToken(' '+token+' '),token)
  for(const value of ['',subject,'rootPassword','Bearer '+token,'rdf_'+'A'.repeat(42)])assert.throws(()=>callerToken(value))
})
test('credential history must match lifecycle and version; missing events or secret fields are rejected',()=>{
  const issue={action:'ISSUE',version:1,reason:'组会验证',operator_name:'原型管理员',occurred_at:'2026-10-04T12:00:00Z'}
  const revoke={...issue,action:'REVOKE',version:2},revoked={...credential,status:'REVOKED',version:2,revoked_at:'2026-10-04T12:01:00Z'}
  assert.deepEqual(credentialEvents([issue],credential),[issue]);assert.deepEqual(credentialEvents([issue,revoke],revoked),[issue,revoke])
  for(const history of [[],[revoke],[{...issue,token}],[{...issue,occurred_at:'invalid'}],[{...issue,operator_name:''}]])assert.throws(()=>credentialEvents(history,credential))
  assert.throws(()=>credentialEvents([issue],revoked));assert.throws(()=>credentialEvents([issue,{...revoke,version:1}],revoked))
})
test('omitted fields/time defer to server policy; explicit field and offset time are preserved',()=>{
  assert.deepEqual(invocationParams({fields:[],range:null,limit:10}),{limit:10,offset:0})
  assert.deepEqual(invocationParams({fields:['container_no','event_time'],range:['2026-09-20T08:10:00+08:00','2026-09-20T08:20:00+08:00'],limit:5,containerNo:' GZPU0000001 '}),{limit:5,offset:0,fields:'container_no,event_time',from:'2026-09-20T00:10:00.000Z',to:'2026-09-20T00:20:00.000Z',containerNo:'GZPU0000001'})
  // An unauthorized but real resource field reaches the server, so this page can demonstrate denial.
  assert.equal(invocationParams({fields:['train_no']}).fields,'train_no')
})
test('query parameters reject invalid limits, duplicates, wildcard/injection and malformed time ranges',()=>{
  for(const form of [{limit:0},{limit:101},{limit:1.5},{fields:['*']},{fields:['container_no','container_no']},{fields:['id;drop']},{fields:'id'},{range:['bad','2026-09-21']},{range:['2026-09-21','2026-09-20']},{range:['2026-09-20']},{containerNo:'x OR 1=1'},{eventType:'FAKE'}])assert.throws(()=>invocationParams(form))
})
test('authorized business response must exactly match the requested projection without leaking extra columns',()=>{
  const body={code:0,data:[{container_no:'GZPU0000001',event_time:'2026-09-20T08:10:00+08:00'}]}
  assert.equal(providerRows(body,['container_no','event_time'],5),body.data)
  for(const data of [[{...body.data[0],train_no:'SECRET'}],[{container_no:'GZPU0000001'}],[{...body.data[0],event_time:{nested:'value'}}],['not-row']])assert.throws(()=>providerRows({code:0,data},['container_no','event_time'],5))
  assert.throws(()=>providerRows({...body,code:1},['container_no','event_time'],5))
  assert.throws(()=>providerRows(body,['container_no','event_time'],0))
  assert.deepEqual(providerRows({code:0,data:[]},['container_no'],10),[])
})
