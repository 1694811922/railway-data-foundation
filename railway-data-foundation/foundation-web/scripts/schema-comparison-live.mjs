import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import {mkdir,writeFile} from 'node:fs/promises'
import {execFileSync} from 'node:child_process'
import {resolve} from 'node:path'
import {randomUUID} from 'node:crypto'
import {demoSeed} from '../src/mock/demoData.js'
const assert=(condition,message)=>{if(!condition)throw new Error(message)}
for(const key of ['LOCAL_MYSQL_HOME','SOURCE_DB_USER','SOURCE_DB_PASSWORD','SOURCE_MIGRATION_URL'])assert(process.env[key],'Missing '+key)
const uri=new URL(process.env.SOURCE_MIGRATION_URL.slice(5))
assert(uri.hostname==='127.0.0.1'&&uri.pathname==='/port_source_db','Only local installation supported')
const table='schema_ui_'+randomUUID().replaceAll('-',''),schema='another_source_test'
assert(/^schema_ui_[a-f0-9]{32}$/.test(table),'Unsafe table identity')
const sql=statement=>execFileSync(resolve(process.env.LOCAL_MYSQL_HOME,'bin/mysql.exe'),
  ['--defaults-extra-file=client.cnf','--default-character-set=utf8mb4','-N','-s'],
  {cwd:resolve('../.runtime/local'),input:statement,encoding:'utf8'})
process.env.VITE_USE_MOCK='false'
const server=await createServer({server:{host:'127.0.0.1',port:5177,strictPort:true}})
const origin='http://127.0.0.1:5177',api='http://127.0.0.1:18080/api/v1'
let browser,releasePending
async function data(response){assert(response.ok(),'HTTP '+response.status());const body=await response.json();assert(body.code===0,'API failed');return body.data}
async function fulfill(route,value){await route.fulfill({json:{code:0,message:'成功',data:value}})}
const snapshot=page=>page.evaluate(()=>JSON.stringify(Object.entries(localStorage).sort()))
try {
  sql(`CREATE TABLE ${schema}.${table}(id BIGINT NOT NULL PRIMARY KEY,code VARCHAR(10) NULL DEFAULT NULL COMMENT '原代码',event_time DATETIME NOT NULL,old_code INT NULL DEFAULT 0) COMMENT='比较测试原表';`)
  await server.listen();await mkdir('.runtime',{recursive:true})
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const context=await browser.newContext({viewport:{width:1660,height:1100},locale:'zh-CN'}),page=await context.newPage(),errors=[]
  page.on('pageerror',error=>errors.push(error.message));page.setDefaultTimeout(12000)
  const source=await data(await page.request.post(api+'/sources',{data:{source_name:'UI_REST_结构比较_'+table,source_type:'MYSQL',
    host:uri.hostname,port:Number(uri.port),database_name:schema,username:process.env.SOURCE_DB_USER,password:process.env.SOURCE_DB_PASSWORD,ssl_mode:'DISABLED'}}))
  await writeFile('.runtime/schema-comparison-live-fixtures.json',JSON.stringify([{source_id:source.source_id,credential_ref:source.credential_ref,source_name:source.source_name}],null,2))
  const scan=async()=>data(await page.request.post(api+'/sources/'+source.source_id+'/scan'))
  const baseline=await scan()
  const resource=await data(await page.request.post(api+'/resources',{data:{resource_name:'UI_REST_结构比较资源',source_id:source.source_id,
    scan_task_id:baseline.task_id,schema_name:schema,physical_table:table,business_description:'临时验收资源',owner_department:'验收组',security_level:'内部',update_mode:'实时'}}))
  const unchanged=await scan(),resourceUrl=api+'/resources/'+resource.resource_id,comparisonUrl=resourceUrl+'/schema-comparison'
  const resourceBefore=await data(await page.request.get(resourceUrl)),fieldsBefore=await data(await page.request.get(resourceUrl+'/fields'))
  sql(`ALTER TABLE ${schema}.${table} DROP PRIMARY KEY,MODIFY id INT NULL,RENAME COLUMN old_code TO new_code,MODIFY code VARCHAR(40) NOT NULL DEFAULT 'active' COMMENT '新代码' AFTER event_time,ADD PRIMARY KEY(code),COMMENT='比较测试新表';`)
  const target=await scan()
  const actual=await data(await page.request.get(comparisonUrl+'?scanTaskId='+target.task_id))
  assert(actual.summary.added===1&&actual.summary.removed===1&&actual.summary.modified===3,'Unexpected real differences')
  await page.goto(origin);await page.getByRole('button',{name:'进入平台'}).click()
  await page.goto(origin+'/resources/'+resource.resource_id,{waitUntil:'networkidle'})
  const panel=page.getByTestId('schema-comparison-panel'),rows=()=>panel.getByTestId('comparison-changes').locator('.el-table__body-wrapper tbody tr')
  await page.getByRole('tab',{name:'结构变化',exact:true}).click()
  await panel.getByRole('button',{name:'开始比较'}).waitFor()
  assert(await panel.getByTestId('comparison-summary').count()===0,'Comparison ran without user action')
  await panel.getByRole('button',{name:'开始比较'}).click();await panel.getByTestId('comparison-summary').waitFor()
  assert((await panel.getByTestId('comparison-origins').textContent()).includes(baseline.task_id),'Wrong baseline shown')
  assert(await rows().count()===5,'Real rows missing')
  await panel.getByLabel('结构变化关键词').fill('code');assert(await rows().count()===3,'Keyword filter failed')
  await panel.locator('.filters .el-select').click();await page.getByRole('option',{name:'修改',exact:true}).click()
  assert(await rows().count()===1,'Type filter failed')
  await rows().first().locator('.el-table__expand-icon').click();await panel.getByTestId('comparison-properties').waitFor()
  assert((await panel.getByTestId('comparison-properties').textContent()).includes('默认值'),'Expanded attributes missing')
  await panel.getByLabel('结构变化关键词').fill('');await panel.locator('.filters .el-select').click()
  await page.keyboard.press('Escape');await panel.locator('.filters .el-select').hover();await panel.locator('.filters .el-select .el-select__clear').click({force:true})
  await page.screenshot({path:'.runtime/schema-comparison-real.png',fullPage:true,animations:'disabled'})
  const stored=await snapshot(page)
  // Real failure clears earlier successful differences; retry uses the same real API.
  await page.route('**/schema-comparison?*',route=>route.fulfill({status:503,json:{code:10005,message:'测试连接失败',data:null}}))
  await panel.getByRole('button',{name:'开始比较'}).click();await panel.getByTestId('comparison-error').waitFor()
  assert(await panel.getByTestId('comparison-summary').count()===0,'Stale success retained after failure')
  assert(await snapshot(page)===stored,'Real failure became a demo write')
  await page.unroute('**/schema-comparison?*');await panel.getByRole('button',{name:'重试比较'}).click();await panel.getByTestId('comparison-summary').waitFor()
  // Candidate failure also clears results and has a visible retry.
  await page.route('**/schema-comparison/scans?*',route=>route.abort('failed'))
  await panel.getByRole('button',{name:'刷新对比扫描'}).click();await panel.getByTestId('comparison-candidate-error').waitFor()
  assert(await panel.getByTestId('comparison-summary').count()===0,'Old result retained after candidate failure')
  await page.unroute('**/schema-comparison/scans?*');await panel.getByRole('button',{name:'刷新对比扫描'}).click()
  await panel.getByTestId('comparison-candidate-error').waitFor({state:'hidden'})
  await panel.getByRole('button',{name:'开始比较'}).click();await panel.getByTestId('comparison-summary').waitFor()
  // Task switches discard old results. Use the actual no-change target.
  await panel.locator('.selector .el-select').click();await page.getByRole('option').filter({hasText:unchanged.task_id}).click()
  assert(await panel.getByTestId('comparison-summary').count()===0,'Switching tasks retained result')
  await panel.getByRole('button',{name:'开始比较'}).click();await panel.getByTestId('comparison-unchanged').waitFor()
  // A selected task change also invalidates a held response within the same panel.
  let taskEntered,releaseTask
  const taskStarted=new Promise(resolve=>{taskEntered=resolve}),taskGate=new Promise(resolve=>{releaseTask=resolve})
  releasePending=releaseTask
  const unchangedResult=await data(await page.request.get(comparisonUrl+'?scanTaskId='+unchanged.task_id))
  await page.route('**/schema-comparison?*',async route=>{taskEntered();await taskGate;await fulfill(route,unchangedResult)})
  await panel.getByRole('button',{name:'开始比较'}).click();await taskStarted
  await panel.locator('.selector .el-select').click();await page.getByRole('option').filter({hasText:target.task_id}).click()
  releaseTask();await page.waitForTimeout(200)
  assert(await panel.getByTestId('comparison-summary').count()===0,'Old task response replaced current selection')
  await page.unroute('**/schema-comparison?*')
  // Hold an actual response, leave the tab, release it and reopen; late response cannot repopulate.
  let entered;const started=new Promise(resolve=>{entered=resolve}),gate=new Promise(resolve=>{releasePending=resolve})
  await page.route('**/schema-comparison?*',async route=>{entered();await gate;await fulfill(route,actual)})
  await panel.getByRole('button',{name:'开始比较'}).click();await started
  await page.getByRole('tab',{name:'基本信息',exact:true}).click();releasePending()
  await page.getByRole('tab',{name:'结构变化',exact:true}).click();await page.waitForTimeout(200)
  assert(await panel.getByTestId('comparison-summary').count()===0,'Late comparison populated reopened tab')
  await page.unroute('**/schema-comparison?*')
  // Read-only UI fixtures cover large result pagination, candidate paging and uncertain scopes.
  const many={...actual,summary:{added:53,removed:0,modified:0,unchanged:0},table_changes:[],changes:Array.from({length:53},(_,i)=>({
    change_type:'ADDED',field_name:'test_field_'+i,before:null,after:{...actual.changes[0].after,field_name:'test_field_'+i,ordinal_position:i+1},changed_properties:[]}))}
  await page.route('**/schema-comparison?*',route=>fulfill(route,many))
  await panel.getByRole('button',{name:'开始比较'}).click();await panel.getByTestId('comparison-summary').waitFor()
  assert(await rows().count()===10,'Default page must contain 10 changes')
  await panel.getByTestId('comparison-pagination').getByRole('button',{name:'下一页'}).click()
  assert((await rows().first().textContent()).includes('test_field_10'),'Page navigation failed')
  await panel.getByTestId('comparison-pagination').locator('.el-select').click();await page.getByRole('option',{name:'20条/页',exact:true}).click()
  assert(await rows().count()===20,'20-row page failed')
  await panel.getByTestId('comparison-pagination').locator('.el-select').click();await page.getByRole('option',{name:'50条/页',exact:true}).click()
  assert(await rows().count()===50,'50-row page failed')
  await panel.getByLabel('结构变化关键词').fill('test_field_52');assert(await rows().count()===1,'Filter did not reset page')
  await page.unroute('**/schema-comparison?*')
  for(const status of ['TABLE_NOT_FOUND','SCOPE_UNCONFIRMED']) {
    await page.route('**/schema-comparison?*',route=>fulfill(route,{...actual,comparison_status:status,summary:null,changes:[],table_changes:[],target_scan:{...actual.target_scan,task_id:unchanged.task_id}}))
    await panel.locator('.selector .el-select').click();await page.getByRole('option').filter({hasText:unchanged.task_id}).click()
    await panel.getByRole('button',{name:'开始比较'}).click();await panel.getByTestId('comparison-unknown').waitFor()
    assert(await panel.getByTestId('comparison-summary').count()===0,'Unknown counts presented as zero')
    await page.unroute('**/schema-comparison?*')
  }
  const candidates=Array.from({length:21},(_,i)=>({...actual.target_scan,task_id:'SCAN_'+(i+1).toString(16).padStart(32,'0')}))
  await page.route('**/schema-comparison/scans?*',route=>{const offset=Number(new URL(route.request().url()).searchParams.get('offset'));return fulfill(route,{items:candidates.slice(offset,offset+20),total:21})})
  await panel.getByRole('button',{name:'刷新对比扫描'}).click();await panel.getByTestId('comparison-scan-pagination').waitFor()
  const secondPage=page.waitForResponse(r=>r.url().includes('schema-comparison/scans')&&r.url().includes('offset=20'))
  await panel.getByTestId('comparison-scan-pagination').getByRole('button',{name:'下一页'}).click();await secondPage
  await panel.locator('.selector .el-select').click();assert(await page.getByRole('option').count()===1,'Candidate page is not limited to its 20-row slice');await page.keyboard.press('Escape')
  await page.unroute('**/schema-comparison/scans?*')
  // Leaving the resource completely destroys the old comparison request scope.
  await panel.getByRole('button',{name:'刷新对比扫描'}).click()
  let resourceEntered,releaseResource
  const resourceStarted=new Promise(resolve=>{resourceEntered=resolve}),resourceGate=new Promise(resolve=>{releaseResource=resolve})
  releasePending=releaseResource
  await page.route('**/schema-comparison?*',async route=>{resourceEntered();await resourceGate;await fulfill(route,actual)})
  await panel.getByRole('button',{name:'开始比较'}).click();await resourceStarted
  await page.evaluate(()=>{history.pushState(history.state,'','/resources/RES_000001');window.dispatchEvent(new PopStateEvent('popstate',{state:history.state}))})
  await page.getByRole('heading',{name:'广州港集装箱作业事件数据集',exact:true}).waitFor();releaseResource()
  await page.getByRole('tab',{name:'结构变化',exact:true}).click();await page.waitForTimeout(200)
  assert(await panel.getByTestId('comparison-summary').count()===0,'Old resource response replaced demo resource')
  await page.unroute('**/schema-comparison?*')
  // Explicit owned demo uses isolated snapshots; legacy demo without baseline is unavailable.
  const demoPage=await context.newPage();await demoPage.goto(origin);await demoPage.getByRole('button',{name:'进入平台'}).click()
  await demoPage.goto(origin+'/resources/RES_000001',{waitUntil:'networkidle'});await demoPage.getByRole('tab',{name:'结构变化',exact:true}).click()
  await demoPage.getByRole('button',{name:'开始比较'}).click();await demoPage.getByTestId('comparison-summary').waitFor()
  assert((await demoPage.locator('body').textContent()).includes('演示'),'Demo marker missing')
  const old=structuredClone(demoSeed);old.resources[0].scan_task_id=null
  await demoPage.evaluate(value=>localStorage.setItem('foundation_frontend_demo_v1',JSON.stringify(value)),old)
  await demoPage.reload({waitUntil:'networkidle'});await demoPage.getByRole('tab',{name:'结构变化',exact:true}).click()
  await demoPage.getByTestId('comparison-candidate-error').waitFor()
  assert((await demoPage.getByTestId('comparison-candidate-error').textContent()).includes('暂不可比较'),'Legacy demo guessed a baseline')
  assert(JSON.stringify(await data(await page.request.get(resourceUrl)))===JSON.stringify(resourceBefore),'Read-only comparison changed resource')
  assert(JSON.stringify(await data(await page.request.get(resourceUrl+'/fields')))===JSON.stringify(fieldsBefore),'Read-only comparison changed fields')
  assert(errors.length===0,'Browser errors: '+errors.join('; '))
  console.log('PASS: real altered-table comparison, no-change, Chinese filters and details, 10/20/50 pagination, candidate paging, failures/retry, late tab response, uncertain scope, demo isolation and unchanged registered metadata')
} finally {
  releasePending?.();await browser?.close();await server.close()
  sql(`DROP TABLE IF EXISTS ${schema}.${table};`)
}
