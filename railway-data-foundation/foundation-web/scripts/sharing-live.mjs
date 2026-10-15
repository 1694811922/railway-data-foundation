import assert from 'node:assert/strict'
import {mkdir,readFile,writeFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
// Opt-in local verification; passwords are only transient API write parameters.
for(const key of ['PROVIDER_SOURCE_DB_URL','PROVIDER_SOURCE_DB_USER','PROVIDER_SOURCE_DB_PASSWORD'])if(!process.env[key])throw new Error('Missing '+key)
const sourceUri=new URL(process.env.PROVIDER_SOURCE_DB_URL.slice(5))
assert.equal(sourceUri.hostname,'127.0.0.1');assert.equal(sourceUri.pathname,'/port_source_db')
process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1',run=crypto.randomUUID().replaceAll('-','').toUpperCase(),prefix='UI_SHARE_'
await mkdir('.runtime',{recursive:true})
const manifest='.runtime/sharing-live-fixtures.json'
let fixtures=[];try{fixtures=JSON.parse(await readFile(manifest,'utf8'))}catch(e){if(e.code!=='ENOENT')throw e}
const fixture={run,source_name:prefix+'source_'+run,provider_code:prefix+'PROVIDER_'+run,demand_code:prefix+'DEMAND_'+run}
fixtures.push(fixture)
async function record(){await writeFile(manifest,JSON.stringify(fixtures,null,2))}
await record()
async function request(path,method='GET',body){
  const response=await fetch(api+path,{method,headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined}),json=await response.json()
  assert.ok(response.ok,`${path} returned ${response.status}: ${json.message}`);assert.equal(json.code,0);return json.data
}
const server=await createServer({server:{host:'127.0.0.1',port:5189,strictPort:true}})
let browser,release
try{
  const source=await request('/sources','POST',{source_name:fixture.source_name,source_type:'MYSQL',host:sourceUri.hostname,port:Number(sourceUri.port),database_name:'port_source_db',username:process.env.PROVIDER_SOURCE_DB_USER,password:process.env.PROVIDER_SOURCE_DB_PASSWORD,ssl_mode:'DISABLED'})
  Object.assign(fixture,{source_id:source.source_id,credential_ref:source.credential_ref});await record()
  const scan=await request(`/sources/${source.source_id}/scan`,'POST');assert.equal(scan.status,'SUCCESS');assert.equal(scan.field_count,12)
  const resource=await request('/resources','POST',{resource_name:prefix+'resource_'+run,source_id:source.source_id,scan_task_id:scan.task_id,schema_name:'port_source_db',physical_table:'container_event',business_description:'共享关系真实浏览器专项的临时元数据',owner_department:'本机测试',security_level:'内部',update_mode:'实时'})
  fixture.resource_id=resource.resource_id;await record()
  await request(`/resources/${resource.resource_id}/fields/event_time`,'PUT',{business_name:'事件时间',business_description:'共享时间条件',time_field:true,sensitivity_level:'内部',resource_version:resource.version})
  await request(`/resources/${resource.resource_id}/publish`,'POST')
  const capability=await request('/capabilities','POST',{resource_id:resource.resource_id,capability_name:prefix+'capability_'+run,type:'REST_API',http_method:'GET',endpoint:'/containers/events',protocol:'HTTP',auth_type:'NONE',description:'仅验证关系登记，Provider共享授权尚待实现'})
  fixture.capability_id=capability.capability_id;await record()
  await request(`/capabilities/${capability.capability_id}/verify`,'POST');await request(`/capabilities/${capability.capability_id}/enable`,'POST')
  for(const role of ['PROVIDER','DEMAND']){
    const subject=await request('/subjects','POST',{subject_code:fixture[role.toLowerCase()+'_code'],subject_name:prefix+role+'_'+run,subject_type:role,owner_department:'本机测试',description:'临时共享主体'})
    fixture[role.toLowerCase()+'_id']=subject.subject_id;await record()
  }
  await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const page=await browser.newPage({viewport:{width:1800,height:1080},locale:'zh-CN'}),errors=[]
  page.on('pageerror',e=>errors.push(e.message))
  const dialog=()=>page.getByRole('dialog',{name:/新增共享关系|编辑共享关系/}),table=()=>page.getByTestId('relationship-table')
  const item=label=>dialog().locator('.el-form-item').filter({has:page.locator('.el-form-item__label').filter({hasText:new RegExp('^\\s*'+label+'$')})})
  async function ready(){await page.waitForFunction(()=>!document.querySelector('.el-loading-mask')&&!document.querySelector('.el-button.is-loading'))}
  async function screenshot(name){
    if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'})
    await page.waitForFunction(()=>Array.from(document.querySelectorAll('.el-drawer')).every(el=>{const value=getComputedStyle(el).transform;return value==='none'||value==='matrix(1, 0, 0, 1, 0, 0)'}))
    await page.screenshot({path:'.runtime/'+name+'.png',fullPage:true,animations:'disabled'})
  }
  async function pick(label,name){await item(label).locator('.el-select').click();await page.getByRole('option',{name,exact:true}).click()}
  async function fill(label,value){await item(label).locator('input,textarea').fill(value)}
  async function saved(){await dialog().waitFor({state:'hidden'});await ready()}
  await page.goto('http://127.0.0.1:5189/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.getByRole('menuitem',{name:'共享治理',exact:true}).click();await page.getByRole('menuitem',{name:'共享关系管理',exact:true}).click();await page.getByRole('heading',{name:'共享关系管理',exact:true}).waitFor();await ready()
  await page.getByRole('button',{name:'新增共享关系',exact:true}).click()
  await fill('关系名称',prefix+'relationship_'+run);await pick('提供方',prefix+'PROVIDER_'+run);await pick('需求方',prefix+'DEMAND_'+run)
  await pick('数据资源',prefix+'resource_'+run);await ready();await pick('接口能力',prefix+'capability_'+run)
  await fill('使用目的','铁路科研分析');await fill('操作原因','登记专项测试关系')
  await dialog().getByRole('button',{name:'保存共享关系',exact:true}).click();await dialog().getByTestId('relationship-form-error').getByText(/允许字段/).waitFor()
  await dialog().locator('.el-checkbox').filter({has:page.getByRole('checkbox',{name:/container_no/})}).click();await dialog().locator('.el-checkbox').filter({has:page.getByRole('checkbox',{name:/event_time/})}).click();await pick('时间条件字段','事件时间')
  const created=page.waitForResponse(r=>r.url().endsWith('/api/v1/relationships')&&r.request().method()==='POST')
  await dialog().getByRole('button',{name:'保存共享关系',exact:true}).click()
  const response=await created;assert.ok(response.ok());const relationship=(await response.json()).data
  fixture.relationship_id=relationship.relationship_id;fixture.policy_id=relationship.policy.policy_id;await record();await saved()
  await page.getByRole('textbox',{name:'搜索共享关系',exact:true}).fill(run)
  const row=()=>table().getByRole('row').filter({hasText:run})
  await row().getByText('草稿',{exact:true}).waitFor()
  async function openAction(label){await row().getByRole('button',{name:'状态操作',exact:true}).hover();await page.getByRole('menuitem',{name:label,exact:true}).click();await page.getByRole('dialog',{name:label,exact:true}).waitFor()}
  async function action(label,status){await openAction(label);const modal=page.getByRole('dialog',{name:label,exact:true});await modal.getByRole('textbox',{name:'状态操作原因'}).fill('专项验证'+label);await modal.getByRole('button',{name:'确认操作',exact:true}).click();await modal.waitFor({state:'hidden'});await ready();await row().getByText(status,{exact:true}).waitFor()}
  await openAction('提交确认');let modal=page.getByRole('dialog',{name:'提交确认',exact:true})
  await modal.getByRole('button',{name:'确认操作',exact:true}).click();await modal.getByTestId('relationship-action-error').getByText(/操作原因/).waitFor()
  const actionUrl=`**/relationships/${fixture.relationship_id}/submit`;let failedWrites=0
  await page.route(actionUrl,route=>{failedWrites++;return route.abort('failed')})
  await modal.getByRole('textbox',{name:'状态操作原因'}).fill('网络失败检查');await modal.getByRole('button',{name:'确认操作',exact:true}).click();await modal.getByTestId('relationship-action-error').getByText(/结果未确认/).waitFor()
  assert.equal(failedWrites,1);assert.equal((await request(`/relationships/${fixture.relationship_id}`)).status,'DRAFT')
  await modal.getByRole('button',{name:'取消',exact:true}).click();await page.unroute(actionUrl)
  await action('提交确认','待确认');await action('退回修改','草稿')
  await row().getByRole('button',{name:'编辑',exact:true}).click();assert.equal(await item('提供方').locator('input').isDisabled(),true)
  await fill('使用目的','调整后的铁路科研分析');await fill('操作原因','退回后调整用途');await dialog().getByRole('button',{name:'保存共享关系',exact:true}).click();await saved()
  await action('提交确认','待确认');await action('确认通过','已确认');await action('启用登记','登记已启用');await action('暂停','已暂停');await action('启用登记','登记已启用');await action('撤回','已撤回')
  assert.equal(await row().getByRole('button',{name:'状态操作',exact:true}).isDisabled(),true)
  const actual=await request(`/relationships/${fixture.relationship_id}`),events=await request(`/relationships/${fixture.relationship_id}/events`)
  assert.equal(actual.status,'REVOKED');assert.equal(actual.version,10);assert.equal(actual.policy.version,2);assert.equal(events.length,10)
  assert.deepEqual(actual.policy.allowed_fields,['container_no','event_time']);assert.ok(events.every(event=>event.reason&&event.operator_name==='原型管理员'))
  await row().getByRole('button',{name:'查看',exact:true}).click()
  await page.getByText('调整后的铁路科研分析',{exact:true}).waitFor();assert.equal(await page.getByTestId('relationship-history').getByRole('row').count(),11)
  await screenshot('sharing-real-detail');await page.getByTestId('relationship-history').scrollIntoViewIfNeeded();await screenshot('sharing-real-history');await page.keyboard.press('Escape')
  await page.getByRole('menuitem',{name:'访问策略',exact:true}).click();await ready();await page.getByRole('textbox',{name:'搜索策略',exact:true}).fill(actual.policy.policy_id)
  await page.getByTestId('policy-table').getByText(actual.policy.policy_id,{exact:true}).waitFor();await page.getByTestId('policy-table').getByRole('button',{name:'查看',exact:true}).click();await page.getByText('含起点，不含终点',{exact:false}).waitFor()
  await screenshot('sharing-real-policy');await page.keyboard.press('Escape')
  // Read failure cannot replace real policies with demonstration policies.
  await page.route('**/access-policies?**',route=>route.abort('failed'));await page.getByRole('button',{name:'刷新策略',exact:true}).click();await page.getByTestId('policy-error').waitFor();assert.equal(await page.getByTestId('policy-table').getByRole('row').count(),1)
  await page.unroute('**/access-policies?**');await page.getByRole('button',{name:'刷新策略',exact:true}).click();await ready()
  await page.getByRole('menuitem',{name:'共享关系管理',exact:true}).click();await ready()
  await page.route('**/relationships?**',route=>route.abort('failed'));await page.getByRole('button',{name:'刷新列表',exact:true}).click();await page.getByTestId('relationship-error').waitFor();assert.equal(await page.getByRole('button',{name:'新增共享关系',exact:true}).isDisabled(),true)
  await page.unroute('**/relationships?**')
  let mode='',holdRead=false,writeCount=0
  const draft={...actual,status:'DRAFT',confirmed_at:null}
  await page.route('**/relationships**',async route=>{
    const request=route.request(),url=new URL(request.url())
    const reply=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10002,message:status===200?'success':'版本已变化，请刷新',data})})
    if(request.method()==='GET'&&url.pathname==='/api/v1/relationships'){
      if(holdRead){holdRead=false;await new Promise(resolve=>{release=resolve})}
      return reply(Number(url.searchParams.get('offset'))===0?[mode==='demo'?{...draft,_demo:true}:draft]:[])
    }
    if(request.method()==='PUT'){
      writeCount++
      if(mode==='late')await new Promise(resolve=>{release=resolve})
      if(mode==='conflict')return reply(null,400)
      return reply({...draft,...request.postDataJSON(),version:draft.version+1,policy:{...draft.policy,version:draft.policy.version+1}})
    }
    return route.continue()
  })
  async function refresh(){await page.getByRole('button',{name:'刷新列表',exact:true}).click();await ready()}
  async function navigate(path){await page.evaluate(path=>{history.pushState({},'',path);dispatchEvent(new PopStateEvent('popstate'))},path)}
  async function gate(){const deadline=Date.now()+10000;while(!release){assert.ok(Date.now()<deadline);await new Promise(resolve=>setTimeout(resolve,20))}}
  await refresh();await row().getByRole('button',{name:'编辑',exact:true}).click();await ready();await fill('操作原因','版本冲突检查')
  mode='conflict';await dialog().getByRole('button',{name:'保存共享关系',exact:true}).click();await dialog().getByTestId('relationship-form-error').getByText(/版本已变化/).waitFor();assert.equal(writeCount,1)
  mode='late';release=undefined;await dialog().getByRole('button',{name:'保存共享关系',exact:true}).click();await gate()
  assert.equal(await item('关系名称').locator('input').isDisabled(),true);assert.equal(await dialog().getByRole('button',{name:'取消',exact:true}).isDisabled(),true)
  await navigate('/about');release();release=undefined;await page.getByRole('heading',{name:'系统说明',exact:true}).waitFor()
  await navigate('/relationships');await page.getByRole('heading',{name:'共享关系管理',exact:true}).waitFor();await ready();assert.equal(await dialog().count(),0)
  mode='demo';await refresh();await page.getByTestId('relationship-error').waitFor();assert.equal(await table().getByRole('row').count(),1)
  mode='';holdRead=true;release=undefined;await page.getByRole('button',{name:'刷新列表',exact:true}).click();await gate();await navigate('/about');release();release=undefined;await page.getByRole('heading',{name:'系统说明',exact:true}).waitFor()
  await navigate('/relationships');await page.getByRole('heading',{name:'共享关系管理',exact:true}).waitFor();await ready();assert.equal(await table().getByRole('row').count(),2);assert.equal(writeCount,2)
  assert.deepEqual(errors,[])
  console.log('Sharing UI passed: real source/scan/resource/REST prerequisite; field selection; submit/return/edit/confirm/activate/pause/revoke; immutable bindings; history/policy; uncertain writes, conflicts, locked forms, stale responses and fail-closed reads.')
}catch(error){const page=browser?.contexts()[0]?.pages()[0];if(page)await page.screenshot({path:'.runtime/sharing-failure.png',fullPage:true}).catch(()=>{});throw error}
finally{release?.();await browser?.close();await server.close();await record()}
