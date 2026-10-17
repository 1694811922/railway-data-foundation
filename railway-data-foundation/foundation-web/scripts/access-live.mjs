import assert from 'node:assert/strict'
import {mkdir,readFile,writeFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
// All credentials stay in process memory; fixture manifests contain metadata identifiers only.
for(const key of ['PROVIDER_SOURCE_DB_URL','PROVIDER_SOURCE_DB_USER','PROVIDER_SOURCE_DB_PASSWORD'])if(!process.env[key])throw new Error('Missing '+key)
const sourceUri=new URL(process.env.PROVIDER_SOURCE_DB_URL.slice(5))
assert.equal(sourceUri.hostname,'127.0.0.1');assert.equal(sourceUri.pathname,'/port_source_db')
process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1',run=crypto.randomUUID().replaceAll('-','').toUpperCase(),prefix='UI_SHARE_'
const manifest='.runtime/sharing-live-fixtures.json'
await mkdir('.runtime',{recursive:true})
let fixtures=[];try{fixtures=JSON.parse(await readFile(manifest,'utf8'))}catch(e){if(e.code!=='ENOENT')throw e}
const fixture={run,source_name:prefix+'source_'+run,provider_code:prefix+'PROVIDER_'+run,demand_code:prefix+'DEMAND_'+run}
fixtures.push(fixture)
const record=()=>writeFile(manifest,JSON.stringify(fixtures,null,2))
await record()
async function request(path,method='GET',body){
  const response=await fetch(api+path,{method,headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined}),json=await response.json()
  assert.ok(response.ok,`${path} returned ${response.status}: ${json.message}`);assert.equal(json.code,0);return json.data
}
const server=await createServer({server:{host:'127.0.0.1',port:5190,strictPort:true}})
let browser,secret='',release
try{
  const source=await request('/sources','POST',{source_name:fixture.source_name,source_type:'MYSQL',host:sourceUri.hostname,port:Number(sourceUri.port),database_name:'port_source_db',username:process.env.PROVIDER_SOURCE_DB_USER,password:process.env.PROVIDER_SOURCE_DB_PASSWORD,ssl_mode:'DISABLED'})
  Object.assign(fixture,{source_id:source.source_id,credential_ref:source.credential_ref});await record()
  const scan=await request(`/sources/${source.source_id}/scan`,'POST');assert.equal(scan.status,'SUCCESS')
  const resource=await request('/resources','POST',{resource_name:prefix+'resource_'+run,source_id:source.source_id,scan_task_id:scan.task_id,schema_name:'port_source_db',physical_table:'container_event',business_description:'共享授权联调的临时元数据',owner_department:'本机测试',security_level:'内部',update_mode:'实时'})
  fixture.resource_id=resource.resource_id;await record()
  for(const [name,label] of [['container_no','集装箱号'],['event_time','事件时间'],['train_no','车次']]){
    const current=await request(`/resources/${resource.resource_id}`)
    await request(`/resources/${resource.resource_id}/fields/${name}`,'PUT',{business_name:label,business_description:'授权联调',time_field:name==='event_time',sensitivity_level:'内部',resource_version:current.version})
  }
  await request(`/resources/${resource.resource_id}/publish`,'POST',{version:resource.version,reason:'独立测试发布'})
  const capability=await request('/capabilities','POST',{resource_id:resource.resource_id,capability_name:prefix+'capability_'+run,type:'REST_API',http_method:'GET',endpoint:'/containers/events',protocol:'HTTP',auth_type:'NONE',description:'可信身份与策略执行联调'})
  fixture.capability_id=capability.capability_id;await record()
  await request(`/capabilities/${capability.capability_id}/verify`,'POST');await request(`/capabilities/${capability.capability_id}/enable`,'POST')
  for(const role of ['PROVIDER','DEMAND']){
    const subject=await request('/subjects','POST',{subject_code:fixture[role.toLowerCase()+'_code'],subject_name:prefix+role+'_'+run,subject_type:role,owner_department:'本机测试',description:'临时共享主体'})
    fixture[role.toLowerCase()+'_id']=subject.subject_id;await record()
  }
  const relation=await request('/relationships','POST',{relationship_name:prefix+'relationship_'+run,provider_subject_id:fixture.provider_id,demand_subject_id:fixture.demand_id,resource_id:fixture.resource_id,capability_id:fixture.capability_id,purpose:'字段和时间授权验证',valid_from:new Date(Date.now()-60000).toISOString(),valid_until:new Date(Date.now()+3600000).toISOString(),reason:'真实联调',policy:{allowed_fields:['container_no','event_time'],time_field:'event_time',data_from:'2026-09-20T00:00:00Z',data_until:'2026-09-21T00:00:00Z'}})
  fixture.relationship_id=relation.relationship_id;fixture.policy_id=relation.policy.policy_id;await record()
  async function action(name){const current=await request(`/relationships/${fixture.relationship_id}`);return request(`/relationships/${fixture.relationship_id}/${name}`,'POST',{version:current.version,reason:'授权联调'+name})}
  await action('submit');await action('confirm');await action('enable')
  await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const page=await browser.newPage({viewport:{width:1800,height:1080},locale:'zh-CN',timezoneId:'Asia/Shanghai'}),errors=[]
  page.on('pageerror',e=>errors.push(e.message))
  // The browser should never directly submit business rows to the Foundation decision API.
  let browserAccessChecks=0;page.on('request',r=>{if(new URL(r.url()).pathname==='/api/v1/access/check')browserAccessChecks++})
  async function ready(){await page.waitForFunction(()=>!document.querySelector('.el-loading-mask')&&!document.querySelector('.el-button.is-loading'))}
  async function navigate(path){await page.evaluate(path=>{history.pushState({},'',path);dispatchEvent(new PopStateEvent('popstate'))},path);await ready()}
  async function screenshot(name){if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'});await page.screenshot({path:'.runtime/'+name+'.png',fullPage:true,animations:'disabled'})}
  await page.goto('http://127.0.0.1:5190/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.getByRole('menuitem',{name:'共享治理',exact:true}).click();await page.getByRole('menuitem',{name:'调用凭据',exact:true}).click();await ready()
  await page.getByRole('button',{name:'签发调用凭据',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'签发调用凭据',exact:true})
  const item=label=>dialog.locator('.el-form-item').filter({has:page.locator('.el-form-item__label').filter({hasText:new RegExp('^\\s*'+label+'$')})})
  await item('需求方').locator('.el-select').click();await page.getByRole('option',{name:prefix+'DEMAND_'+run,exact:true}).click()
  await item('凭据名称').locator('input').fill(prefix+'credential_'+run);await item('签发原因').locator('textarea').fill('组会共享授权联调')
  const signed=page.waitForResponse(r=>r.url().endsWith(`/subjects/${fixture.demand_id}/credentials`)&&r.request().method()==='POST')
  await dialog.getByRole('button',{name:'确认签发',exact:true}).click();const signedResponse=await signed;assert.ok(signedResponse.ok())
  const issued=(await signedResponse.json()).data;fixture.caller_credential_id=issued.credential.credential_id;await record()
  await dialog.getByLabel('本次调用令牌',{exact:true}).waitFor();secret=await dialog.getByLabel('本次调用令牌',{exact:true}).inputValue()
  assert.ok(/^rdf_[A-Za-z0-9_-]{43}$/.test(secret),'One-time caller token format is invalid');assert.ok(secret===issued.token,'One-time response does not match visible token');assert.equal(await dialog.getByLabel('本次调用令牌',{exact:true}).getAttribute('type'),'password')
  await screenshot('access-real-credential-masked')
  await dialog.getByRole('button',{name:'已保存，关闭',exact:true}).click();await dialog.waitFor({state:'hidden'})
  const metadata=await request(`/caller-credentials/${fixture.caller_credential_id}`);assert.equal(Object.hasOwn(metadata,'token'),false);assert.equal(Object.hasOwn(metadata,'token_digest'),false)
  await page.getByRole('menuitem',{name:'共享调用验证',exact:true}).click();await ready()
  await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:'选择共享关系',exact:true})}).click();await page.getByRole('option',{name:prefix+'relationship_'+run+' · 登记已启用',exact:true}).click();await ready()
  await page.getByLabel('需求方调用令牌',{exact:true}).fill(secret)
  const result=()=>page.getByTestId('access-result')
  const observations=[]
  async function invoke(status){
    const responsePromise=page.waitForResponse(r=>new URL(r.url()).pathname==='/provider/containers/events')
    await page.getByRole('button',{name:'发送共享查询',exact:true}).click()
    const response=await responsePromise;assert.equal(response.status(),status)
    const trace=response.headers()['x-trace-id'];assert.match(trace,/^[a-f0-9-]{36}$/)
    const body=await response.body(),payload=JSON.parse(body.toString('utf8'))
    const deadline=Date.now()+10000;let event
    while(!event&&Date.now()<deadline){
      const history=await request('/runtime-events?'+new URLSearchParams({traceId:trace,limit:'2'}))
      assert.ok(history.total<=1,'A real call was counted twice');event=history.rows[0]
      if(!event)await new Promise(resolve=>setTimeout(resolve,100))
    }
    assert.ok(event,'Automatic Provider report did not reach Foundation')
    assert.equal(event.http_status,status);assert.equal(event.collection_point,'REST_PROVIDER')
    assert.equal(event.outcome,status===200?'SUCCESS':'DENIED')
    assert.equal(event.record_count,status===200?payload.data.length:0)
    assert.equal(event.byte_count,status===200?body.length:0)
    if(status===200){assert.equal(event.subject_id,fixture.demand_id);assert.equal(event.resource_id,fixture.resource_id);assert.equal(event.policy_id,fixture.policy_id);assert.ok(event.relationship_version>0)}
    if(status===401)assert.equal(event.subject_id,null)
    assert.ok(!JSON.stringify(event).includes(secret)&&!JSON.stringify(event).includes('GZPU0000001'),'Report contains credentials or a business value')
    const traceRows=await request('/runtime-traces/'+trace);assert.equal(traceRows.length,1);assert.equal(traceRows[0].event_id,event.event_id)
    observations.push(event)
    await result().getByText(new RegExp('HTTP '+status)).waitFor();await ready()
  }
  await invoke(200);let returned=await page.getByTestId('access-result-table').getByRole('columnheader').allTextContents();assert.deepEqual(returned.map(value=>value.trim()),['集装箱号','事件时间'])
  assert.equal(await page.getByTestId('access-result-table').getByRole('row').count(),11)
  // The source DATETIME is interpreted as +08:00; UI input is serialized to UTC.
  const range=page.locator('.el-date-editor--datetimerange input')
  await range.nth(0).fill('2026-09-20 08:10:00');await range.nth(1).fill('2026-09-20 08:20:00');await range.nth(1).press('Tab')
  await invoke(200);assert.equal(await page.getByTestId('access-result-table').getByRole('row').count(),2);await page.getByTestId('access-result-table').getByText('GZPU0000001',{exact:true}).waitFor()
  await screenshot('access-real-allowed')
  await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:'请求字段',exact:true})}).click();await page.getByRole('option',{name:'车次（train_no）',exact:true}).click();await page.keyboard.press('Escape')
  await invoke(403);assert.equal(await page.getByTestId('access-result-table').count(),0)
  await screenshot('access-real-forbidden-field')
  await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:'请求字段',exact:true})}).locator('.el-tag__close').click()
  await range.nth(0).fill('2026-09-19 08:10:00');await range.nth(1).fill('2026-09-20 08:20:00');await range.nth(1).press('Tab');await invoke(403)
  // Reload resets filters, keeps only the manually held token and re-reads real policy metadata.
  await page.getByRole('button',{name:'刷新关系',exact:true}).click();await ready();await invoke(200)
  await action('suspend');await invoke(403);await page.getByRole('button',{name:'刷新关系',exact:true}).click();await ready();await result().waitFor({state:'hidden'})
  await action('enable');await invoke(200)
  await page.getByLabel('需求方调用令牌',{exact:true}).fill('rdf_'+'z'.repeat(43));await invoke(401)
  await page.getByLabel('需求方调用令牌',{exact:true}).fill(secret)
  // A late successful response cannot show records after leaving the page.
  let settleHeld;const heldSettled=new Promise(resolve=>{settleHeld=resolve})
  await page.route('**/provider/containers/events?**',async route=>{try{const response=await route.fetch();await new Promise(resolve=>{release=resolve});await route.fulfill({response})}finally{settleHeld()}})
  await page.getByRole('button',{name:'发送共享查询',exact:true}).click()
  const deadline=Date.now()+10000;while(!release){assert.ok(Date.now()<deadline);await new Promise(resolve=>setTimeout(resolve,20))}
  await navigate('/credentials');release();release=undefined;await heldSettled;await page.unroute('**/provider/containers/events?**');await ready()
  await action('revoke');await navigate('/access-check?relationshipId='+fixture.relationship_id);await ready();await page.getByLabel('需求方调用令牌',{exact:true}).fill(secret);await invoke(403)
  await screenshot('access-real-withdrawn');await navigate('/credentials');await ready()
  const credentialRow=page.getByTestId('credential-table').getByRole('row').filter({hasText:fixture.caller_credential_id})
  await credentialRow.getByRole('button',{name:'撤销',exact:true}).click();const revoke=page.getByRole('dialog',{name:'撤销调用凭据',exact:true})
  await revoke.getByRole('textbox',{name:'撤销凭据原因'}).fill('验证凭据撤销即时拒绝');await revoke.getByRole('button',{name:'确认撤销',exact:true}).click();await revoke.waitFor({state:'hidden'});await ready()
  await navigate('/access-check?relationshipId='+fixture.relationship_id);await ready();assert.equal(await page.getByLabel('需求方调用令牌',{exact:true}).inputValue(),'')
  assert.equal(await page.getByTestId('access-result-table').count(),0);await page.getByLabel('需求方调用令牌',{exact:true}).fill(secret);await invoke(401)
  const history=await request(`/caller-credentials/${fixture.caller_credential_id}/events`);assert.deepEqual(history.map(event=>event.action),['ISSUE','REVOKE'])
  // Legacy capability-only requests have no valid sharing identity or binding.
  const legacy=await fetch('http://127.0.0.1:18081/containers/events',{headers:{'X-Capability-Id':fixture.capability_id}});assert.notEqual(legacy.status,200)
  const browserStorage=await page.evaluate(()=>({session:JSON.stringify({...sessionStorage}),local:JSON.stringify({...localStorage})}));assert.ok(!JSON.stringify(browserStorage).includes(secret));assert.equal(browserAccessChecks,0);assert.deepEqual(errors,[])
  const start=new Date(Math.floor(Math.min(...observations.map(e=>Date.parse(e.completed_at)))/60000)*60000).toISOString()
  const until=new Date(Math.floor(Math.max(...observations.map(e=>Date.parse(e.completed_at)))/60000)*60000+60000).toISOString()
  const params={relationshipId:fixture.relationship_id,collectionPoint:'REST_PROVIDER',from:start,until}
  const events=await request('/runtime-events?'+new URLSearchParams({...params,limit:'200'}))
  const windows=await request('/metrics/windows?'+new URLSearchParams(params))
  assert.equal(windows.reduce((n,w)=>n+(w.calls??0),0),events.total)
  assert.equal(windows.reduce((n,w)=>n+(w.records??0),0),events.rows.reduce((n,e)=>n+e.record_count,0))
  assert.equal(windows.reduce((n,w)=>n+(w.bytes??0),0),events.rows.reduce((n,e)=>n+e.byte_count,0))
  const unknown=await request('/metrics/windows?'+new URLSearchParams({collectionPoint:'KAFKA_PRODUCER',resourceId:fixture.resource_id,from:start,until}))
  assert.ok(unknown.every(w=>w.state==='UNKNOWN'&&w.messages===null),'Unobserved scoped traffic was represented as zero')
  const collectorResponse=await fetch('http://127.0.0.1:18081/api/v1/provider/telemetry');const collector=(await collectorResponse.json()).data
  assert.equal(collector.enabled,true);assert.equal(collector.storage_ready,true);assert.equal(collector.dropped_events,0);assert.equal(collector.rejected_reports,0)
  console.log('Access and automatic telemetry passed: real allow/deny/revoke; exact projected rows and UTF-8 response bytes; trusted identity; unique persisted traces; per-minute counts match actual calls; Kafka unknown stays null. No business rows or caller token in reports.')
}catch(error){const page=browser?.contexts()[0]?.pages()[0];if(page)await page.screenshot({path:'.runtime/access-failure.png',fullPage:true,animations:'disabled'}).catch(()=>{});throw error}
finally{secret='';release?.();await browser?.close();await server.close();await record()}
