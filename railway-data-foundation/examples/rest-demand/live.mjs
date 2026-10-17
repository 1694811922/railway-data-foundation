// Opt-in real API verification. Creates only guarded temporary Foundation metadata.
// Source rows stay untouched. No business response or caller token is written to disk.
import assert from 'node:assert/strict';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createDemandClient, DemandError } from './client.mjs';

const project=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const manifest=resolve(project,'.runtime/demand-rest-fixtures.json');
const evidence=resolve(project,'.runtime/demand-rest-verification.json');
const api='http://127.0.0.1:18080/api/v1',providerOrigin='http://127.0.0.1:18081';
if(process.platform!=='win32')throw new Error('This guarded local verification uses the project Windows cleanup script.');
for(const key of ['PROVIDER_SOURCE_DB_URL','PROVIDER_SOURCE_DB_USER','PROVIDER_SOURCE_DB_PASSWORD'])if(!process.env[key])throw new Error('Load local environment before verification: '+key);
const sourceUri=new URL(process.env.PROVIDER_SOURCE_DB_URL.slice(5));
assert.equal(sourceUri.hostname,'127.0.0.1');assert.equal(sourceUri.pathname,'/port_source_db');
await mkdir(dirname(manifest),{recursive:true});
let fixtures=[];try{fixtures=JSON.parse(await readFile(manifest,'utf8'))}catch(error){if(error.code!=='ENOENT')throw error}
const run=randomUUID().replaceAll('-','').toUpperCase(),prefix='UI_SHARE_';
const fixture={run,source_name:prefix+'source_'+run,provider_code:prefix+'PROVIDER_'+run,demand_code:prefix+'DEMAND_'+run};
const save=()=>writeFile(manifest,JSON.stringify(fixtures,null,2));
async function request(path,method='GET',body){
  const response=await fetch(api+path,{method,headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined,redirect:'error',signal:AbortSignal.timeout(10000)});
  const json=await response.json();assert.ok(response.ok,`Foundation ${path}: HTTP ${response.status}`);assert.equal(json.code,0,`Foundation ${path}: nonzero code`);return json.data;
}
const checks=[],observations=[];
let secret='',failure,cleanupFailed=false;
try{
  assert.equal((await request('/health')).status,'UP');
  const health=await fetch(providerOrigin+'/api/v1/provider/health');assert.equal((await health.json()).code,0);
  fixtures.push(fixture);await save();
  const source=await request('/sources','POST',{source_name:fixture.source_name,source_type:'MYSQL',host:sourceUri.hostname,port:Number(sourceUri.port),database_name:'port_source_db',username:process.env.PROVIDER_SOURCE_DB_USER,password:process.env.PROVIDER_SOURCE_DB_PASSWORD,ssl_mode:'DISABLED'});
  Object.assign(fixture,{source_id:source.source_id,credential_ref:source.credential_ref});await save();
  const scan=await request(`/sources/${source.source_id}/scan`,'POST');assert.equal(scan.status,'SUCCESS');
  const resource=await request('/resources','POST',{resource_name:prefix+'resource_'+run,source_id:source.source_id,scan_task_id:scan.task_id,schema_name:'port_source_db',physical_table:'container_event',business_description:'V0.5 外部 REST 调用方接入验证临时元数据',owner_department:'本机联调',security_level:'内部',update_mode:'实时'});
  fixture.resource_id=resource.resource_id;await save();
  await request(`/resources/${resource.resource_id}/fields/event_time`,'PUT',{business_name:'事件时间',business_description:'数据时间范围',time_field:true,sensitivity_level:'内部',resource_version:resource.version});
  await request(`/resources/${resource.resource_id}/publish`,'POST',{version:resource.version,reason:'独立测试发布'});
  const capability=await request('/capabilities','POST',{resource_id:resource.resource_id,capability_name:prefix+'capability_'+run,type:'REST_API',http_method:'GET',endpoint:'/containers/events',protocol:'HTTP',auth_type:'NONE',description:'外部调用方联调'});
  fixture.capability_id=capability.capability_id;await save();
  await request(`/capabilities/${capability.capability_id}/verify`,'POST');await request(`/capabilities/${capability.capability_id}/enable`,'POST');
  for(const role of ['PROVIDER','DEMAND']){
    const subject=await request('/subjects','POST',{subject_code:fixture[role.toLowerCase()+'_code'],subject_name:prefix+role+'_'+run,subject_type:role,owner_department:'本机联调',description:'外部调用方接入验证临时主体'});
    fixture[role.toLowerCase()+'_id']=subject.subject_id;await save();
  }
  const relation=await request('/relationships','POST',{relationship_name:prefix+'relationship_'+run,provider_subject_id:fixture.provider_id,demand_subject_id:fixture.demand_id,resource_id:fixture.resource_id,capability_id:fixture.capability_id,purpose:'外部调用方授权与计量联调',valid_from:new Date(Date.now()-60000).toISOString(),valid_until:new Date(Date.now()+3600000).toISOString(),reason:'独立接入示例',policy:{allowed_fields:['container_no','event_time'],time_field:'event_time',data_from:'2026-09-20T00:00:00Z',data_until:'2026-09-21T00:00:00Z'}});
  Object.assign(fixture,{relationship_id:relation.relationship_id,policy_id:relation.policy.policy_id});await save();
  async function action(name){const current=await request(`/relationships/${fixture.relationship_id}`);return request(`/relationships/${fixture.relationship_id}/${name}`,'POST',{version:current.version,reason:'独立接入示例'+name})}
  await action('submit');await action('confirm');await action('enable');
  const issued=await request(`/subjects/${fixture.demand_id}/credentials`,'POST',{credential_name:prefix+'credential_'+run,expires_at:new Date(Date.now()+3600000).toISOString(),reason:'仅本次独立接入验证'});
  fixture.caller_credential_id=issued.credential.credential_id;secret=issued.token;await save();
  const client=createDemandClient({capabilityId:fixture.capability_id,relationshipId:fixture.relationship_id,token:secret});
  await client.discover();
  const before={resource:await request(`/resources/${fixture.resource_id}`),fields:await request(`/resources/${fixture.resource_id}/fields`),capability:await request(`/capabilities/${fixture.capability_id}`),policy:relation.policy};
  const summary=await client.consume({fields:['container_no','event_time'],pageSize:7,maxPages:10,onPage(rows){assert.ok(rows.every(row=>Object.keys(row).join(',')==='container_no,event_time'))}});
  assert.equal(summary.recordCount,20);assert.equal(summary.limitReached,false);assert.deepEqual(summary.observations.map(o=>o.recordCount),[7,7,6]);
  observations.push(...summary.observations.map(o=>({...o,status:200})));checks.push('真实三页读取20条，仅允许字段');
  const timePage=await client.queryPage({fields:['container_no','event_time'],from:'2026-09-20T00:10:00Z',to:'2026-09-20T00:20:00Z'});
  assert.equal(timePage.rows.length,1);observations.push({traceId:timePage.traceId,recordCount:1,responseBytes:timePage.responseBytes,status:200});checks.push('带时区的策略内时间范围');
  async function denied(options,status){
    let error;try{await client.queryPage(options)}catch(value){error=value}
    assert.ok(error instanceof DemandError);assert.equal(error.status,status);assert.ok(error.traceId);assert.ok(!JSON.stringify(error).includes(secret));
    observations.push({traceId:error.traceId,recordCount:0,responseBytes:0,status});
  }
  await denied({fields:['train_no']},403);checks.push('越权字段403');
  await denied({from:'2026-09-19T00:00:00Z'},403);checks.push('越权时间403');
  await action('suspend');await denied({},403);checks.push('暂停关系后403');
  await action('enable');const resumed=await client.queryPage({limit:1});assert.equal(resumed.rows.length,1);
  observations.push({traceId:resumed.traceId,recordCount:1,responseBytes:resumed.responseBytes,status:200});checks.push('恢复关系后允许');
  // Exercise the user-facing executable without logging or passing the caller secret in arguments.
  const cli=spawnSync(process.execPath,[resolve(project,'examples/rest-demand/cli.mjs')],{encoding:'utf8',env:{SystemRoot:process.env.SystemRoot,PATH:process.env.PATH,DEMAND_FOUNDATION_URL:'http://127.0.0.1:18080',DEMAND_PROVIDER_ORIGIN:providerOrigin,DEMAND_CAPABILITY_ID:fixture.capability_id,DEMAND_RELATIONSHIP_ID:fixture.relationship_id,DEMAND_CALLER_TOKEN:secret,DEMAND_FIELDS:'container_no,event_time',DEMAND_FROM:'',DEMAND_TO:'',DEMAND_PAGE_SIZE:'3',DEMAND_MAX_PAGES:'1'},timeout:15000});
  assert.equal(cli.status,0,'CLI invocation failed');assert.match(cli.stdout,/已达到读取页数上限/);assert.ok(!cli.stdout.includes(secret)&&!cli.stderr.includes(secret)&&!cli.stdout.includes('GZPU'));
  const cliTrace=cli.stdout.match(/追踪编号 ([a-f0-9-]{36})/)[1];observations.push({traceId:cliTrace,recordCount:3,responseBytes:null,status:200});checks.push('命令行真实运行，仅输出数量与追踪编号');
  // Exercise the PowerShell wrapper; replace only the interactive prompt in this child process.
  // The real token remains in the child's environment, never in command arguments or files.
  const wrapperPath=resolve(project,'scripts/run-demand-rest-example.ps1').replaceAll("'","''");
  const wrapperCode=`$ErrorActionPreference='Stop'; function Read-Host { param([string]$Prompt,[switch]$AsSecureString) ConvertTo-SecureString $env:EXAMPLE_TEST_TOKEN -AsPlainText -Force }; & '${wrapperPath}' -CapabilityId $env:DEMAND_CAPABILITY_ID -RelationshipId $env:DEMAND_RELATIONSHIP_ID -Fields 'container_no,event_time' -PageSize 1 -MaxPages 1; if ($env:DEMAND_CALLER_TOKEN) { throw 'Temporary environment not cleared' }`;
  const wrapped=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-Command',wrapperCode],{encoding:'utf8',env:{SystemRoot:process.env.SystemRoot,PATH:process.env.PATH,PATHEXT:process.env.PATHEXT,EXAMPLE_TEST_TOKEN:secret,DEMAND_CAPABILITY_ID:fixture.capability_id,DEMAND_RELATIONSHIP_ID:fixture.relationship_id},timeout:15000});
  if(wrapped.status!==0)await writeFile(resolve(project,'.runtime/demand-wrapper-diagnostic.txt'),(wrapped.stderr||'No error output').replaceAll(secret,'[redacted]'));
  assert.equal(wrapped.status,0,'PowerShell terminal wrapper failed');assert.ok(!wrapped.stdout.includes(secret)&&!wrapped.stderr.includes(secret));
  const wrapperTrace=wrapped.stdout.match(/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}/)?.[0];assert.ok(wrapperTrace);
  observations.push({traceId:wrapperTrace,recordCount:1,responseBytes:null,status:200});checks.push('PowerShell安全输入包装与临时环境恢复');
  const currentCredential=await request(`/caller-credentials/${fixture.caller_credential_id}`);
  await request(`/caller-credentials/${fixture.caller_credential_id}/revoke`,'POST',{version:currentCredential.version,reason:'联调结束，验证凭据撤销'});
  await denied({},401);checks.push('撤销凭据后401');
  const deadline=Date.now()+15000,events=[];
  for(const observation of observations){
    let event;
    while(Date.now()<deadline){const page=await request('/runtime-events?'+new URLSearchParams({traceId:observation.traceId,limit:'2'}));assert.ok(page.total<=1);event=page.rows[0];if(event)break;await new Promise(resolve=>setTimeout(resolve,100))}
    assert.ok(event,'Automatic telemetry was not received');assert.equal(event.http_status,observation.status);assert.equal(event.record_count,observation.recordCount);
    if(observation.responseBytes!==null)assert.equal(event.byte_count,observation.responseBytes);
    assert.equal(event.collection_point,'REST_PROVIDER');assert.equal(event.outcome,observation.status===200?'SUCCESS':'DENIED');
    if(observation.status===200){assert.equal(event.subject_id,fixture.demand_id);assert.equal(event.resource_id,fixture.resource_id);assert.equal(event.policy_id,fixture.policy_id)}
    if(observation.status===401)assert.equal(event.subject_id,null);
    assert.ok(!JSON.stringify(event).includes(secret)&&!JSON.stringify(event).includes('GZPU'));
    const trace=await request('/runtime-traces/'+observation.traceId);assert.equal(trace.length,1);assert.equal(trace[0].event_id,event.event_id);events.push(event);
  }
  const start=new Date(Math.floor(Math.min(...events.map(e=>Date.parse(e.completed_at)))/60000)*60000).toISOString();
  const until=new Date(Math.floor(Math.max(...events.map(e=>Date.parse(e.completed_at)))/60000)*60000+60000).toISOString();
  const windows=await request('/metrics/windows?'+new URLSearchParams({relationshipId:fixture.relationship_id,collectionPoint:'REST_PROVIDER',from:start,until}));
  assert.equal(windows.reduce((n,w)=>n+(w.calls??0),0),events.length);
  assert.equal(windows.reduce((n,w)=>n+(w.records??0),0),events.reduce((n,e)=>n+e.record_count,0));
  assert.equal(windows.reduce((n,w)=>n+(w.bytes??0),0),events.reduce((n,e)=>n+e.byte_count,0));
  checks.push('逐请求自动审计、唯一追踪和分钟数量/字节核对');
  assert.deepEqual(await request(`/resources/${fixture.resource_id}`),before.resource);assert.deepEqual(await request(`/resources/${fixture.resource_id}/fields`),before.fields);assert.deepEqual(await request(`/capabilities/${fixture.capability_id}`),before.capability);assert.deepEqual((await request(`/relationships/${fixture.relationship_id}`)).policy,before.policy);
  checks.push('资源版本/字段/能力/确认策略保持不变');
  await writeFile(evidence,JSON.stringify({verified_at:new Date().toISOString(),run,checks,observed_calls:events.length,shared_records:events.reduce((n,e)=>n+e.record_count,0),shared_bytes:events.reduce((n,e)=>n+e.byte_count,0),traces:events.map(e=>e.trace_id),scope:'REST_PROVIDER only; no consumer-side telemetry'},null,2));
}catch(error){failure=error}
finally{
  secret='';
  // No general deletion endpoint is invented. Use the existing exact-name/ownership SQL guard.
  if(fixtures.some(f=>f.run===run)){
    const cleanup=spawnSync('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',resolve(project,'scripts/cleanup-sharing-ui-test.ps1'),'-FixtureManifest','demand-rest-fixtures.json'],{cwd:project,encoding:'utf8',timeout:30000});
    if(cleanup.status!==0){cleanupFailed=true;console.error('临时元数据清理未完成；保留编号清单，请运行专用清理命令。')}
    else {await writeFile(manifest,'[]\n');console.log('临时共享元数据已按归属校验清理，原有源表与运行审计保留。')}
  }
}
if(failure||cleanupFailed){console.error('外部 REST 接入实际验证失败；未模拟成功。');if(failure)console.error(failure instanceof DemandError?failure.message:failure instanceof assert.AssertionError?failure.message:'验证步骤异常，检查本地服务状态');process.exitCode=1}
else console.log(`外部 REST 接入验证通过：${checks.join('；')}。`);
