import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
import {spawnSync} from 'node:child_process'
import {resolve} from 'node:path'
import {createServer as createTcpServer} from 'node:net'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'

// Opt-in local approval UI test. Metadata fixture does not assert Broker health or grant any ACL.
// Only the random fixture is changed; no Source business table is created, read or modified.
if(!process.env.SUBSCRIPTION_MYSQL_EXE)throw new Error('Set SUBSCRIPTION_MYSQL_EXE to the local mysql.exe path')
process.env.VITE_USE_MOCK='false'
const run=crypto.randomUUID().replaceAll('-',''),tag='UI_KSUB_'+run
const ids=Object.fromEntries(['source','scan','resource','capability','provider','demand'].map(key=>[key,({source:'SRC',scan:'SCAN',resource:'RES',capability:'CAP',provider:'SUB',demand:'SUB'})[key]+'_'+crypto.randomUUID().replaceAll('-','')]))
const api='http://127.0.0.1:18080/api/v1',mysql=process.env.SUBSCRIPTION_MYSQL_EXE,localRuntime=resolve('../.runtime/local')
function sql(statement){const result=spawnSync(mysql,['--defaults-extra-file=client.cnf','--batch','--skip-column-names','foundation_db','-e',statement],{cwd:localRuntime,encoding:'utf8'});if(result.status!==0)throw new Error('Local test metadata operation failed ('+(result.stderr?.match(/ERROR \d+/)?.[0]||result.error?.code||result.status)+')');return result.stdout.trim()}
async function request(path,method='GET',body){const r=await fetch(api+path,{method,headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined});const b=await r.json();assert.equal(r.ok,true,`${path}: ${r.status}, ${b.message}`);assert.equal(b.code,0);return b.data}
const body=name=>({grant_name:name,provider_subject_id:ids.provider,demand_subject_id:ids.demand,resource_id:ids.resource,capability_id:ids.capability,purpose:'真实接口审批专项，未执行Broker授权',valid_from:new Date(Date.now()-60000).toISOString(),valid_until:new Date(Date.now()+3600000).toISOString(),whole_topic_acknowledged:true,reason:'明确接受完整消息及历史'})
await mkdir('.runtime',{recursive:true})
const manifest=`.runtime/subscription-fixture-${run}.json`
await writeFile(manifest,JSON.stringify({run,tag,...ids,cleaned:false},null,2))
async function freePort(){const socket=createTcpServer();await new Promise((done,fail)=>socket.once('error',fail).listen(0,'127.0.0.1',done));const port=socket.address().port;await new Promise(done=>socket.close(done));return port}
const server=await createServer({server:{host:'127.0.0.1',port:await freePort(),strictPort:true}})
let browser,release
try{
  sql(`INSERT INTO sharing_subject(subject_id,subject_code,subject_name,subject_type,owner_department,status,created_at,updated_at) VALUES('${ids.provider}','${tag}_P','${tag}_P','PROVIDER','UI test','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),('${ids.demand}','${tag}_D','${tag}_D','DEMAND','UI test','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
    INSERT INTO source_system(source_id,source_name,source_type,connection_env_prefix,description,status,created_at,updated_at) VALUES('${ids.source}','${tag}','MYSQL','UI_FIXTURE','No business connection','ENABLED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
    INSERT INTO metadata_scan_task(task_id,source_id,status,table_count,started_at,finished_at) VALUES('${ids.scan}','${ids.source}','SUCCESS',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
    INSERT INTO metadata_scan_table(task_id,schema_name,table_name,table_comment,field_count) VALUES('${ids.scan}','ui_fixture','subscription_fixture','Approval fixture only',0);
    INSERT INTO data_resource(resource_id,source_id,scan_task_id,schema_name,physical_table,resource_name,business_description,owner_department,security_level,update_mode,status,created_at,updated_at) VALUES('${ids.resource}','${ids.source}','${ids.scan}','ui_fixture','subscription_fixture','${tag}_R','Approval test only','UI test','内部','实时','PUBLISHED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));
    INSERT INTO capability(capability_id,resource_id,capability_name,type,topic,protocol,auth_type,status,health_status,created_at,updated_at) VALUES('${ids.capability}','${ids.resource}','${tag}_C','KAFKA_TOPIC','ui.subscription.${run}','KAFKA','SASL','ENABLED','UP',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));`)
  await server.listen();const base=`http://127.0.0.1:${server.httpServer.address().port}`
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const page=await browser.newPage({viewport:{width:1800,height:1080},locale:'zh-CN'}),errors=[]
  page.on('pageerror',e=>errors.push(e.message))
  await page.goto(base);await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.getByRole('menuitem',{name:'共享治理',exact:true}).click();await page.getByRole('menuitem',{name:'共享授权',exact:true}).click();await page.getByRole('tab',{name:'Kafka订阅',exact:true}).click()
  const table=()=>page.getByTestId('subscription-table'),edit=()=>page.getByRole('dialog',{name:/新增订阅申请|编辑订阅申请/})
  const ready=async()=>{await page.waitForFunction(()=>!document.querySelector('[data-testid="subscription-table"] .el-loading-mask'));await page.getByRole('button',{name:'新增订阅申请',exact:true}).waitFor();await page.waitForFunction(()=>[...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='新增订阅申请'&&!b.disabled))}
  const choose=async(label,value)=>{await edit().locator('.el-select').filter({has:page.getByRole('combobox',{name:label,exact:true})}).click();await page.getByRole('option',{name:value,exact:true}).click()}
  await ready();await page.getByRole('button',{name:'新增订阅申请',exact:true}).click()
  await edit().getByRole('textbox',{name:'授权名称',exact:true}).fill(tag+'_main')
  await choose('提供方',tag+'_P');await choose('需求方',tag+'_D');await choose('数据资源',tag+'_R');await choose('Kafka 能力',tag+'_C')
  await edit().getByRole('textbox',{name:'使用目的',exact:true}).fill('科研整主题审批');await edit().getByRole('textbox',{name:'操作原因',exact:true}).fill('专项登记')
  await edit().getByRole('button',{name:'保存申请',exact:true}).click();await edit().getByTestId('subscription-form-error').getByText(/完整消息/).waitFor()
  await edit().locator('.el-checkbox').click();const created=page.waitForResponse(r=>r.url().endsWith('/api/v1/kafka-subscriptions')&&r.request().method()==='POST')
  await edit().getByRole('button',{name:'保存申请',exact:true}).click();const createdResponse=await created;assert.equal(createdResponse.ok(),true);const grant=(await createdResponse.json()).data
  await edit().waitFor({state:'hidden'});await ready()
  const search=async text=>{await page.getByRole('textbox',{name:'搜索订阅授权',exact:true}).fill(text);const response=page.waitForResponse(r=>r.url().includes('/kafka-subscriptions?')&&r.request().method()==='GET');await page.getByRole('button',{name:'查询',exact:true}).click();await response;await ready()}
  const row=()=>table().getByRole('row').filter({hasText:tag+'_main'})
  await search(tag+'_main');await row().getByText('草稿',{exact:true}).waitFor()
  async function openAction(label){await row().getByRole('button',{name:'审批操作',exact:true}).hover();await page.getByRole('menuitem',{name:label,exact:true}).click();return page.getByRole('dialog',{name:label,exact:true})}
  async function action(label,status){const modal=await openAction(label);await modal.getByRole('textbox',{name:'审批操作原因',exact:true}).fill('专项'+label);if(label==='确认整主题范围')await modal.locator('.el-checkbox').click();await modal.getByRole('button',{name:'确认操作',exact:true}).click();await modal.waitFor({state:'hidden'});await ready();await row().getByText(status,{exact:true}).waitFor()}
  await action('提交确认','待确认');await action('退回修改','草稿')
  await row().getByRole('button',{name:'编辑',exact:true}).click();assert.equal(await edit().getByRole('combobox',{name:'Kafka 能力',exact:true}).isDisabled(),true)
  await edit().getByRole('textbox',{name:'使用目的',exact:true}).fill('调整后的科研用途');await edit().getByRole('textbox',{name:'操作原因',exact:true}).fill('退回后修改');await edit().getByRole('button',{name:'保存申请',exact:true}).click();await edit().waitFor({state:'hidden'});await ready()
  await action('提交确认','待确认')
  let modal=await openAction('确认整主题范围');await modal.getByRole('textbox',{name:'审批操作原因',exact:true}).fill('确认完整消息和历史');await modal.getByRole('button',{name:'确认操作',exact:true}).click();await modal.getByTestId('subscription-action-error').getByText(/完整消息/).waitFor();await modal.locator('.el-checkbox').click();await modal.getByRole('button',{name:'确认操作',exact:true}).click();await modal.waitFor({state:'hidden'});await ready();await row().getByText('已确认',{exact:true}).waitFor()
  assert.equal((await request('/kafka-subscriptions/'+grant.grant_id)).broker_access_confirmed,false)
  await row().getByRole('button',{name:'查看',exact:true}).click();await page.getByRole('heading',{name:'操作历史',exact:true}).waitFor();await page.screenshot({path:'.runtime/subscription-approved.png',fullPage:true,animations:'disabled'});await page.getByRole('button',{name:'关闭详情',exact:true}).click()
  await action('撤回申请','已撤回申请');assert.equal(await row().getByRole('button',{name:'审批操作',exact:true}).count(),0)
  assert.equal((await request('/kafka-subscriptions/'+grant.grant_id)).execution_state,'NOT_PROVISIONED')
  for(let n=0;n<22;n++)await request('/kafka-subscriptions','POST',body(tag+'_page_'+n))
  await search(tag);assert.equal(await table().locator('tbody tr').count(),20);await page.locator('.el-pagination .btn-next').click();await ready();await page.waitForFunction(()=>document.querySelectorAll('[data-testid="subscription-table"] tbody tr').length===3)
  await search(tag+'_page_0');const draftRow=table().getByRole('row').filter({hasText:tag+'_page_0'})
  const stale=(await request('/kafka-subscriptions?keyword='+tag+'_page_0')).items[0]
  await draftRow.getByRole('button',{name:'编辑',exact:true}).click();await edit().getByRole('textbox',{name:'使用目的',exact:true}).fill('冲突时保留我的输入');await edit().getByRole('textbox',{name:'操作原因',exact:true}).fill('并发编辑验证')
  await request('/kafka-subscriptions/'+stale.grant_id+'/submit','POST',{version:stale.version,reason:'另一管理页面先提交'})
  await edit().getByRole('button',{name:'保存申请',exact:true}).click();await edit().getByTestId('subscription-form-error').getByText(/已变化/).waitFor();assert.equal(await edit().getByRole('button',{name:'保存申请',exact:true}).isDisabled(),true);assert.equal(await edit().getByRole('textbox',{name:'使用目的',exact:true}).inputValue(),'冲突时保留我的输入');await edit().getByRole('button',{name:'取消',exact:true}).click()
  await search(tag+'_main')
  // Failure injection is read-only; no successful workflow response is intercepted.
  await page.route('**/kafka-subscriptions?**',route=>route.abort('failed'));await page.getByRole('button',{name:'查询',exact:true}).click();await page.getByTestId('subscription-error').waitFor();assert.equal(await table().locator('tbody tr').count(),0);await page.unroute('**/kafka-subscriptions?**');await page.getByRole('button',{name:'重试',exact:true}).click();await ready();await row().waitFor()
  let held=false,finishHeld,routeError
  const heldComplete=new Promise(done=>{finishHeld=done})
  await page.route('**/kafka-subscriptions?**',async route=>{
    if(new URL(route.request().url()).searchParams.get('keyword')===tag+'_main'&&!held){held=true;try{const response=await route.fetch();await new Promise(done=>{release=done});await route.fulfill({response})}catch(e){routeError=e}finally{finishHeld()}}else await route.continue()
  })
  await page.getByRole('button',{name:'查询',exact:true}).click();await page.waitForFunction(()=>!!document.querySelector('[data-testid="subscription-table"] .el-loading-mask'))
  for(let attempt=0;attempt<100&&!release;attempt++)await new Promise(done=>setTimeout(done,20))
  assert.equal(typeof release,'function');await search(tag+'_no_match');release();release=undefined;await heldComplete;assert.equal(routeError,undefined);await page.unroute('**/kafka-subscriptions?**');await page.waitForTimeout(150);assert.equal(await table().locator('tbody tr').count(),0)
  await search(tag);await page.screenshot({path:'.runtime/subscription-catalog.png',fullPage:true,animations:'disabled'})
  assert.deepEqual(errors,[])
  // Explicit demo mode gets an empty workflow and no real reads or writes.
  const demo=await createServer({define:{'import.meta.env.VITE_USE_MOCK':'"true"'},server:{host:'127.0.0.1',port:await freePort(),strictPort:true}})
  try{await demo.listen();const demoPage=await browser.newPage();await demoPage.addInitScript(()=>sessionStorage.setItem('foundation_demo_login','1'));let calls=0;demoPage.on('request',r=>{if(r.url().includes('/api/v1/'))calls++});await demoPage.goto(`http://127.0.0.1:${demo.httpServer.address().port}/kafka-subscriptions`);await demoPage.getByText('订阅审批仅支持真实接口，请关闭演示模式后使用。',{exact:true}).waitFor();assert.equal(await demoPage.getByRole('button',{name:'新增订阅申请',exact:true}).isDisabled(),true);assert.equal(calls,0);await demoPage.close()}finally{await demo.close()}
  console.log('Subscription approval UI passed: real create/submit/return/edit/confirm/revoke/history, explicit scope consent, immutable bindings, paging, failure retry, stale read isolation and demo separation. Broker execution remains NOT_PROVISIONED.')
}catch(error){const page=browser?.contexts()[0]?.pages()[0];if(page)await page.screenshot({path:'.runtime/subscription-failure.png',fullPage:true}).catch(()=>{});throw error}
finally{
  release?.();await browser?.close();await server.close()
  // Fixed local database and exact random binding IDs; preserve all unrelated rows and Source data.
  sql(`DELETE e FROM kafka_subscription_event e JOIN kafka_subscription_grant g ON g.grant_id=e.grant_id WHERE g.resource_id='${ids.resource}' AND g.capability_id='${ids.capability}';
    DELETE FROM kafka_subscription_grant WHERE resource_id='${ids.resource}' AND capability_id='${ids.capability}';
    DELETE FROM capability WHERE capability_id='${ids.capability}' AND resource_id='${ids.resource}';
    DELETE FROM data_resource WHERE resource_id='${ids.resource}' AND source_id='${ids.source}';
    DELETE FROM metadata_scan_table WHERE task_id='${ids.scan}' AND schema_name='ui_fixture' AND table_name='subscription_fixture';
    DELETE FROM metadata_scan_task WHERE task_id='${ids.scan}' AND source_id='${ids.source}';
    DELETE FROM source_system WHERE source_id='${ids.source}' AND source_name='${tag}';
    DELETE FROM sharing_subject WHERE subject_id IN ('${ids.provider}','${ids.demand}') AND subject_code IN ('${tag}_P','${tag}_D');`)
  assert.equal(sql(`SELECT COUNT(*) FROM kafka_subscription_grant WHERE resource_id='${ids.resource}';`),'0')
  await writeFile(manifest,JSON.stringify({run,tag,...ids,cleaned:true},null,2))
}
