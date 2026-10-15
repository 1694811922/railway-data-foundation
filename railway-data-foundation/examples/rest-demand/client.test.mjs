import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { createDemandClient, DemandError } from './client.mjs';

const token = 'rdf_' + 't'.repeat(43), capabilityId = 'CAP_TEST123', relationshipId = 'REL_TEST123';
const config = { token, capabilityId, relationshipId };
async function fixture(t, handler) {
  const requests = [];
  const server = createServer((req, res) => { requests.push({url:req.url,headers:req.headers}); handler(req,res) });
  await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
  t.after(() => { server.closeAllConnections(); return new Promise(resolve => server.close(resolve)) });
  const origin = `http://127.0.0.1:${server.address().port}`;
  const client = createDemandClient({ ...config, foundationUrl: origin, providerOrigin: origin });
  return { client, origin, requests };
}
function reply(res, data, {status=200,code=0,trace=true,message='success'}={}) {
  res.writeHead(status,{'Content-Type':'application/json',...(trace?{'X-Trace-Id':randomUUID()}:{})}); res.end(JSON.stringify({code,message,data}));
}
const invocation = origin => ({capability_id:capabilityId,http_method:'GET',endpoint:'/containers/events',request_url:origin+'/containers/events',enabled:true});
function handler(query) { return (req,res) => req.url.includes('/invocation') ? reply(res,invocation('http://'+req.headers.host)) : query(req,res) }
const kind = expected => error => error instanceof DemandError && error.kind === expected && !JSON.stringify(error).includes(token);

test('不合法地址、编号、凭据与超时参数在发送前拒绝', () => {
  for (const options of [{providerOrigin:'http://example.com'},{foundationUrl:'https://user:secret@example.com'},{providerOrigin:'ftp://localhost'},{providerOrigin:'http://localhost/path'},{capabilityId:'CAP_../x'},{relationshipId:''},{token:'service-token'},{timeoutMs:0}]) assert.throws(()=>createDemandClient({...config,...options}),kind('CONFIG'));
});
test('调用说明向可信底座发送需求方身份与关系；业务查询不携带运维凭据', async t => {
  const f=await fixture(t,handler((req,res)=>reply(res,[{container_no:'SAMPLE',event_time:'2026-09-20T08:10:00+08:00'}])));
  await f.client.discover(); const page=await f.client.queryPage({fields:['container_no','event_time'],limit:7,offset:14});
  assert.equal(f.requests[0].headers.authorization,`Bearer ${token}`);
  assert.equal(f.requests[0].headers['x-relationship-id'],relationshipId);
  assert.equal(f.requests[0].headers['x-provider-token'],undefined);
  assert.equal(f.requests[1].headers.authorization,`Bearer ${token}`);
  assert.equal(f.requests[1].headers['x-relationship-id'],relationshipId); assert.equal(f.requests[1].headers['x-capability-id'],capabilityId);
  assert.equal(f.requests[1].headers['x-provider-token'],undefined);
  assert.match(f.requests[1].url,/limit=7&offset=14/); assert.equal(page.rows.length,1); assert.ok(page.responseBytes>0);
});
test('完整白名单包含当前物理表十二字段', async t => {
  const names=['id','container_no','event_type','event_time','port_code','yard_code','train_no','operation_status','equipment_no','source_system','created_at','updated_at'];
  const f=await fixture(t,handler((req,res)=>reply(res,[Object.fromEntries(names.map(name=>[name,null]))])));
  await f.client.discover(); assert.equal((await f.client.queryPage({fields:names})).rows.length,1);
});
test('发现跨站地址或不同能力时不向目标发送调用令牌', async t => {
  const f=await fixture(t,(req,res)=>reply(res,{...invocation('https://other.example'),capability_id:capabilityId}));
  await assert.rejects(f.client.discover(),kind('PROTOCOL')); await assert.rejects(f.client.queryPage(),kind('CONFIG')); assert.equal(f.requests.length,1);
});
test('能力停用以及说明错配均阻止业务请求', async t => {
  let wrong=false;
  const f=await fixture(t,(req,res)=>reply(res,{...invocation('http://'+req.headers.host),enabled:wrong?true:false,capability_id:wrong?'CAP_OTHER':capabilityId}));
  await assert.rejects(f.client.discover(),kind('UNAVAILABLE')); wrong=true; await assert.rejects(f.client.discover(),kind('PROTOCOL')); assert.equal(f.requests.length,2);
});
test('重定向不跟随，不向重定向地址泄露身份', async t => {
  const f=await fixture(t,handler((req,res)=>{res.writeHead(302,{Location:'/secret-target'});res.end()}));
  await f.client.discover(); await assert.rejects(f.client.queryPage(),kind('REDIRECT')); assert.equal(f.requests.length,2);
});
test('401、403、503保留HTTP状态和追踪编号，不反射远端错误正文', async t => {
  let status=401;
  const f=await fixture(t,handler((req,res)=>reply(res,token,{status,message:'sensitive '+token})));
  await f.client.discover();
  for(const value of [401,403,503]) {status=value;await assert.rejects(f.client.queryPage(),e=>kind('HTTP')(e)&&e.status===value&&typeof e.traceId==='string'&&!e.message.includes(token))}
  assert.equal(f.requests.length,4);
});
test('非零业务码不模拟成功、不自动重试', async t => {
  const f=await fixture(t,handler((req,res)=>reply(res,[],{code:10001,message:token})));
  await f.client.discover(); await assert.rejects(f.client.queryPage(),e=>kind('BUSINESS')(e)&&e.code===10001); assert.equal(f.requests.length,2);
});
test('越权附加字段、嵌套业务值、超页数量和缺少追踪编号不交付结果', async t => {
  let mode=0;
  const f=await fixture(t,handler((req,res)=>reply(res,mode===0?[{container_no:'x',train_no:'private'}]:mode===1?[{container_no:{secret:'x'}}]:mode===2?Array.from({length:3},()=>({container_no:'x'})):[{container_no:'x'}],{trace:mode!==3})));
  await f.client.discover();
  for(mode=0;mode<4;mode++) await assert.rejects(f.client.queryPage({fields:['container_no'],limit:2}),kind('PROTOCOL'));
});
test('非法JSON、缺少统一响应字段、超大响应被拒绝', async t => {
  let mode=0;
  const f=await fixture(t,handler((req,res)=>{res.writeHead(200,{'X-Trace-Id':randomUUID()});res.end(mode===0?'bad JSON':mode===1?'{"code":0,"data":[]}':'x'.repeat(1024*1024+1))}));
  await f.client.discover(); for(mode=0;mode<3;mode++)await assert.rejects(f.client.queryPage(),kind('PROTOCOL'));
});
test('参数边界、通配符、SQL及无时区时间在本机拦截', async t => {
  const f=await fixture(t,handler((req,res)=>reply(res,[]))); await f.client.discover();
  for(const options of [{limit:101},{offset:-1},{fields:[]},{fields:['*']},{fields:['id','id']},{fields:['SELECT id']},{from:'2026-09-20T08:00:00'},{from:'2026-02-30T08:00:00Z'},{from:'2026-09-20T24:00:00Z'},{from:'2026-09-20T00:00:00Z',to:'2026-09-19T00:00:00Z'}]) await assert.rejects(f.client.queryPage(options),kind('CONFIG'));
  assert.equal(f.requests.length,1);
});
test('分页顺序读取、按短页结束，摘要不含业务行', async t => {
  const f=await fixture(t,handler((req,res)=>reply(res,Array.from({length:new URL(req.url,'http://local').searchParams.get('offset')==='0'?2:1},()=>({id:1})))));
  await f.client.discover(); const pages=[];
  const summary=await f.client.consume({fields:['id'],pageSize:2,maxPages:5,onPage:(rows,meta)=>pages.push(meta.offset)});
  assert.deepEqual(pages,[0,2]); assert.equal(summary.recordCount,3); assert.equal(summary.limitReached,false); assert.equal(Object.hasOwn(summary.observations[0],'rows'),false);
});
test('达到最大页数只表示停止，空页表示本次读取零条', async t => {
  let empty=false;const f=await fixture(t,handler((req,res)=>reply(res,empty?[]:[{id:1}])));await f.client.discover();
  assert.equal((await f.client.consume({pageSize:1,maxPages:2})).limitReached,true);
  empty=true;const zero=await f.client.consume(); assert.equal(zero.recordCount,0);assert.equal(zero.limitReached,false);assert.equal(zero.observations.length,1);
});
test('中途拒绝停止后续页，不回滚或重新发送已经处理的页', async t => {
  const f=await fixture(t,handler((req,res)=>reply(res,[{id:1}],{status:req.url.includes('offset=1')?403:200})));await f.client.discover();let delivered=0;
  await assert.rejects(f.client.consume({pageSize:1,onPage:()=>delivered++}),kind('HTTP'));assert.equal(delivered,1);assert.equal(f.requests.length,3);
});
test('请求超时停止且不重试；主动取消不会交付晚到页面', async t => {
  const f=await fixture(t,handler((req,res)=>setTimeout(()=>reply(res,[{id:1}]),150)));
  const short=createDemandClient({...config,foundationUrl:f.origin,providerOrigin:f.origin,timeoutMs:50});await short.discover();await assert.rejects(short.queryPage(),kind('TRANSPORT'));
  await f.client.discover();const controller=new AbortController();let delivered=0;const read=f.client.consume({signal:controller.signal,onPage:()=>delivered++});setTimeout(()=>controller.abort(),10);
  await assert.rejects(read,kind('CANCELLED'));assert.equal(delivered,0);
});
test('未发现说明不能调用；页面处理失败停止后续读取', async t => {
  const f=await fixture(t,handler((req,res)=>reply(res,[{id:1}])));await assert.rejects(f.client.queryPage(),kind('CONFIG'));await f.client.discover();
  await assert.rejects(f.client.consume({pageSize:1,onPage:()=>{throw new Error('local handler failed')}}),/local handler failed/);assert.equal(f.requests.length,2);
});
