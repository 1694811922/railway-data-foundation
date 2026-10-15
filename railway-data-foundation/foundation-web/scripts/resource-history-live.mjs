import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import {mkdir,writeFile} from 'node:fs/promises'
const assert=(condition,message)=>{if(!condition)throw new Error(message)}
for(const key of ['SOURCE_MIGRATION_URL','SOURCE_DB_USER','SOURCE_DB_PASSWORD'])assert(process.env[key],'Missing '+key)
const uri=new URL(process.env.SOURCE_MIGRATION_URL.slice(5));assert(uri.hostname==='127.0.0.1'&&uri.pathname==='/port_source_db','Local fixtures only')
process.env.VITE_USE_MOCK='false'
const server=await createServer({server:{host:'127.0.0.1',port:5177,strictPort:true}}),origin='http://127.0.0.1:5177',api='http://127.0.0.1:18080/api/v1'
let browser,release
async function data(response){assert(response.ok(),'HTTP '+response.status());const body=await response.json();assert(body.code===0,'API failed');return body.data}
try{
  await mkdir('.runtime',{recursive:true});await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const context=await browser.newContext({viewport:{width:1660,height:1100},locale:'zh-CN'}),page=await context.newPage(),errors=[]
  page.on('pageerror',error=>errors.push(error.message));page.setDefaultTimeout(12000)
  const source=await data(await page.request.post(api+'/sources',{data:{source_name:'UI_REST_版本历史_'+Date.now(),source_type:'MYSQL',host:uri.hostname,port:Number(uri.port),database_name:'port_source_db',username:process.env.SOURCE_DB_USER,password:process.env.SOURCE_DB_PASSWORD,ssl_mode:'DISABLED'}}))
  await writeFile('.runtime/history-live-fixtures.json',JSON.stringify([{source_id:source.source_id,credential_ref:source.credential_ref,source_name:source.source_name}],null,2))
  const scan=await data(await page.request.post(api+'/sources/'+source.source_id+'/scan'))
  const form={resource_name:'UI_REST_资源版本历史',source_id:source.source_id,scan_task_id:scan.task_id,schema_name:'port_source_db',physical_table:'container_event',business_description:'原始业务说明',owner_department:'验收组',security_level:'内部',update_mode:'实时'}
  const resource=await data(await page.request.post(api+'/resources',{data:form})),url=api+'/resources/'+resource.resource_id
  const first=await data(await page.request.get(url+'/versions/1'))
  await data(await page.request.put(url,{data:{...form,resource_name:'UI_REST_历史修改后的资源',version:1}}))
  await data(await page.request.put(url+'/fields/event_time',{data:{business_name:'人工确认发生时间',business_description:'历史字段说明',time_field:true,sensitivity_level:'敏感',resource_version:2}}))
  await data(await page.request.post(url+'/publish'))
  const before=await data(await page.request.get(url)),fields=await data(await page.request.get(url+'/fields'))
  await page.goto(origin);await page.getByRole('button',{name:'进入平台'}).click();await page.goto(origin+'/resources/'+resource.resource_id,{waitUntil:'networkidle'})
  await page.getByRole('tab',{name:'版本历史',exact:true}).click()
  const panel=page.getByTestId('resource-history-panel'),rows=()=>panel.getByTestId('history-list').locator('.el-table__body-wrapper tbody tr')
  await panel.getByTestId('history-resource').waitFor();assert(await rows().count()===4,'Missing real version entries')
  assert((await panel.getByTestId('history-changes').textContent()).includes('已发布'),'Publication change missing')
  await rows().filter({hasText:'V3'}).getByRole('button').click();await panel.getByTestId('history-resource').waitFor()
  await page.waitForFunction(()=>document.querySelector('[data-testid="history-changes"]')?.textContent.includes('人工确认发生时间'))
  assert((await panel.getByTestId('history-changes').textContent()).includes('敏感等级'),'Field business change missing')
  await page.screenshot({path:'.runtime/history-real.png',fullPage:true,animations:'disabled'})
  await rows().filter({hasText:'V1'}).getByRole('button').click();await panel.getByTestId('history-no-previous').waitFor()
  assert((await panel.getByTestId('history-resource').textContent()).includes(form.resource_name),'Historic name replaced by current value')
  assert(JSON.stringify(await data(await page.request.get(url+'/versions/1')))===JSON.stringify(first),'Old snapshot changed')
  const stored=await page.evaluate(()=>JSON.stringify({...localStorage}))
  await page.route('**/versions/2',route=>route.fulfill({status:503,json:{code:10005,message:'测试读取故障',data:null}}))
  await rows().filter({hasText:'V2'}).getByRole('button').click();await panel.getByTestId('history-detail-error').waitFor()
  assert(await panel.getByTestId('history-resource').count()===0,'Failure retained old snapshot')
  assert(await page.evaluate(()=>JSON.stringify({...localStorage}))===stored,'Real failure wrote demo history')
  await page.unroute('**/versions/2');await panel.getByRole('button',{name:'重试读取快照'}).click();await panel.getByTestId('history-resource').waitFor()
  let entered;const started=new Promise(resolve=>{entered=resolve}),gate=new Promise(resolve=>{release=resolve})
  await page.route('**/versions/1',async route=>{entered();await gate;await route.fulfill({json:{code:0,message:'success',data:first}})})
  await rows().filter({hasText:'V1'}).getByRole('button').click();await started
  await rows().filter({hasText:'V4'}).getByRole('button').click();await panel.getByTestId('history-resource').waitFor();release();await page.waitForTimeout(200)
  assert((await panel.locator('h3').first().textContent()).includes('V4'),'Late version replaced selected snapshot')
  await page.unroute('**/versions/1')
  await page.route('**/versions?*',route=>route.abort('failed'));await panel.getByRole('button',{name:'刷新版本历史'}).click();await panel.getByTestId('history-list-error').waitFor()
  assert(await panel.getByTestId('history-resource').count()===0,'List failure retained previous details')
  await page.unroute('**/versions?*');await panel.getByRole('button',{name:'刷新版本历史'}).click();await panel.getByTestId('history-resource').waitFor()
  for(let version=4;version<=23;version++)await data(await page.request.put(url+'/fields/event_time',{data:{business_name:'历史修改'+version,business_description:'',time_field:true,sensitivity_level:'内部',resource_version:version}}))
  await panel.getByRole('button',{name:'刷新版本历史'}).click();await panel.getByTestId('history-list-pagination').waitFor()
  assert(await rows().count()===20,'First version page not 20 items')
  await panel.getByTestId('history-list-pagination').getByRole('button',{name:'下一页'}).click();await page.waitForFunction(()=>document.querySelectorAll('[data-testid="history-list"] .el-table__body-wrapper tbody tr').length===4)
  await rows().filter({hasText:'V3'}).getByRole('button').click();await panel.getByTestId('history-resource').waitFor()
  await panel.getByLabel('历史字段关键词').fill('event_time');assert(await panel.getByTestId('history-fields').locator('.el-table__body-wrapper tbody tr').count()===1,'History field filter failed')
  const current=await data(await page.request.get(url)),currentFields=await data(await page.request.get(url+'/fields'))
  await panel.getByRole('button',{name:'刷新版本历史'}).click();await panel.getByTestId('history-resource').waitFor()
  assert(JSON.stringify(await data(await page.request.get(url)))===JSON.stringify(current),'History read mutated Resource')
  assert(JSON.stringify(await data(await page.request.get(url+'/fields')))===JSON.stringify(currentFields),'History read mutated Fields')
  assert(before.status==='PUBLISHED'&&fields.find(row=>row.field_name==='event_time').business_name==='人工确认发生时间','Real edits not persisted')
  // Exit tab while a held history response is outstanding.
  await page.route('**/versions/1',async route=>{await new Promise(resolve=>{release=resolve});await route.fulfill({json:{code:0,message:'success',data:first}})})
  await rows().filter({hasText:'V1'}).getByRole('button').click();await page.waitForTimeout(100)
  await page.getByRole('tab',{name:'基本信息',exact:true}).click();release?.();await page.getByRole('tab',{name:'版本历史',exact:true}).click()
  await panel.getByTestId('history-resource').waitFor();assert((await panel.locator('h3').first().textContent()).includes('V24'),'Late response survived tab exit')
  await page.unroute('**/versions/1')
  const demoPage=await context.newPage();await demoPage.goto(origin);await demoPage.getByRole('button',{name:'进入平台'}).click();await demoPage.goto(origin+'/resources/RES_000001',{waitUntil:'networkidle'});await demoPage.getByRole('tab',{name:'版本历史',exact:true}).click();await demoPage.getByTestId('history-baseline').waitFor()
  assert((await demoPage.locator('body').textContent()).includes('演示'),'Demo mode has no marker')
  assert(errors.length===0,'Browser errors '+errors.join('; '))
  console.log('PASS: real create/edit/field/publish snapshots; immutable old content; Chinese history and changes; list paging/filter; failures and retry; late selection/tab isolation; unchanged current state; explicit demo baseline')
}finally{release?.();await browser?.close();await server.close()}
