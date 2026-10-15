import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { mkdir, writeFile } from 'node:fs/promises'

// Opt-in: creates uniquely named source/scan metadata in the local development Foundation.
// Fixtures are listed without secrets in .runtime/source-live-fixtures.json for guarded cleanup.
for(const name of ['TEST_SOURCE_DB_URL','TEST_SECOND_SOURCE_DB_URL','TEST_PROVIDER_SOURCE_DB_USER','TEST_PROVIDER_SOURCE_DB_PASSWORD'])
  if(!process.env[name])throw new Error(`Missing ${name}`)
process.env.VITE_USE_MOCK='false'
const api=process.env.FOUNDATION_API_URL || 'http://127.0.0.1:18080/api/v1'
const created=[],run=Date.now().toString(36)
const server=await createServer({server:{host:'127.0.0.1',port:5175,strictPort:true}})
let browser
function config(name,variable){const uri=new URL(process.env[variable].slice(5));return{
  source_name:name,source_type:'MYSQL',host:uri.hostname,port:Number(uri.port),database_name:uri.pathname.slice(1),
  username:process.env.TEST_PROVIDER_SOURCE_DB_USER,password:process.env.TEST_PROVIDER_SOURCE_DB_PASSWORD,ssl_mode:'DISABLED'
}}
function assert(condition,message){if(!condition)throw new Error(message)}
async function screenshot(page,name){await page.locator('.el-message').last().waitFor({state:'hidden'});await page.screenshot({path:`.runtime/${name}.png`,fullPage:true})}
async function body(response){assert(response.ok(),`Unexpected API HTTP ${response.status()}`);const json=await response.json();assert(json.code===0,'API returned an error');assert(!Object.hasOwn(json.data,'password'),'API echoed password');return json.data}
async function recordSource(source){
  created.push({source_id:source.source_id,credential_ref:source.credential_ref,source_name:source.source_name})
  await mkdir('.runtime',{recursive:true});await writeFile('.runtime/source-live-fixtures.json',JSON.stringify(created,null,2))
}
try{
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const page=await browser.newPage({viewport:{width:1600,height:1050},locale:'zh-CN'})
  const more=async(row,name)=>{await row.getByRole('button',{name:/更多操作/}).click();await page.getByRole('menuitem',{name,exact:true}).click()}
  const errors=[];page.on('pageerror',e=>errors.push(e.message))
  await page.goto('http://127.0.0.1:5175/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台'}).click()
  await page.goto('http://127.0.0.1:5175/sources',{waitUntil:'networkidle'})
  assert(await page.getByTestId('demo-notice').count()===0,'Unexpected mock fallback')
  await page.getByRole('button',{name:'新增数据源'}).click()
  const dialog=page.getByRole('dialog'),portConfig=config(`UI_DYNAMIC_港口_${run}`,'TEST_SOURCE_DB_URL')
  for(const [label,key] of [['数据源名称','source_name'],['数据库地址','host'],['数据库名称','database_name'],['数据库用户名','username'],['数据库密码','password']])
    await dialog.getByLabel(label,{exact:true}).fill(portConfig[key])
  await dialog.getByRole('spinbutton').fill(String(portConfig.port))
  await dialog.getByText('优先加密（默认）',{exact:true}).click();await page.getByRole('option',{name:'不加密（仅本机实验）',exact:true}).click()
  await mkdir('.runtime',{recursive:true});await screenshot(page,'source-dialog')
  const responsePromise=page.waitForResponse(r=>r.url().endsWith('/api/v1/sources')&&r.request().method()==='POST')
  await dialog.getByRole('button',{name:'保存并测试连接'}).click()
  const portSource=await body(await responsePromise);await recordSource(portSource)
  await page.getByText('连接成功',{exact:true}).first().waitFor()
  assert(await page.evaluate(secret=>Object.values(localStorage).every(v=>!v.includes(secret)),portConfig.password),'Password persisted in browser')
  const dispatchConfig=config(`UI_DYNAMIC_调度_${run}`,'TEST_SECOND_SOURCE_DB_URL')
  const dispatchSource=await body(await page.request.post(`${api}/sources`,{data:dispatchConfig}));await recordSource(dispatchSource)
  await page.reload({waitUntil:'networkidle'})
  const portRow=page.getByRole('row').filter({hasText:portConfig.source_name})
  await more(portRow,'编辑配置')
  assert(await dialog.getByLabel('数据库密码',{exact:true}).inputValue()==='','Edit echoed a password')
  const updatePromise=page.waitForResponse(r=>r.url().endsWith(`/sources/${portSource.source_id}`)&&r.request().method()==='PUT')
  await dialog.getByRole('button',{name:'保存并测试连接'}).click();await body(await updatePromise)
  await page.getByText('连接成功',{exact:true}).first().waitFor()
  await more(portRow,'停用数据源');await portRow.getByText('已停用',{exact:true}).waitFor()
  assert(await portRow.getByRole('button',{name:'开始扫描'}).isDisabled(),'Stopped source can scan')
  await more(portRow,'启用数据源');await portRow.getByText('已启用',{exact:true}).waitFor()
  const testUrl=`**/sources/${portSource.source_id}/test`
  await page.route(testUrl,route=>route.abort('timedout'))
  await portRow.getByRole('button',{name:'测试连接',exact:true}).click()
  await page.getByText('连接测试请求失败，请检查后端服务',{exact:true}).first().waitFor()
  assert(await page.getByText('演示连接成功',{exact:true}).count()===0,'Real connection failure became a mock success')
  await page.unroute(testUrl)
  await screenshot(page,'source-list')
  const scanPromise=page.waitForResponse(r=>r.url().endsWith(`/sources/${portSource.source_id}/scan`))
  await portRow.getByRole('button',{name:'开始扫描'}).click();const portTask=await body(await scanPromise)
  assert(portTask.status==='SUCCESS'&&portTask.field_count===12,'Port scan failed or wrong field count')
  await page.getByRole('heading',{name:'元数据扫描'}).waitFor();await page.getByText('container_event',{exact:true}).first().waitFor()
  await page.getByRole('button',{name:'查看字段',exact:true}).first().click()
  await page.getByText('container_no',{exact:true}).waitFor();await page.getByText('event_time',{exact:true}).waitFor()
  assert((await page.getByTestId('scan-field-count').textContent()).includes('12'),'Real fields count missing')
  assert(await page.getByTestId('scan-field-list').getByRole('row').count()===11,'Real fields are not paged at ten')
  await page.getByTestId('scan-field-pager').getByRole('button',{name:'下一页',exact:true}).click()
  await page.getByText('updated_at',{exact:true}).waitFor()
  assert(await page.getByTestId('scan-field-list').getByRole('row').count()===3,'Second page must show remaining two fields')
  await page.getByTestId('scan-field-pager').getByRole('button',{name:'上一页',exact:true}).click()
  await page.getByText('container_no',{exact:true}).waitFor()
  await screenshot(page,'source-fields')
  const dispatchTask=await body(await page.request.post(`${api}/sources/${dispatchSource.source_id}/scan`))
  assert(dispatchTask.status==='SUCCESS'&&dispatchTask.field_count===3,'Dispatch scan failed or wrong count')
  await page.goto(`http://127.0.0.1:5175/scans?task=${dispatchTask.task_id}`,{waitUntil:'networkidle'})
  await page.getByText('train_dispatch',{exact:true}).waitFor()
  assert(await page.getByText('container_event',{exact:true}).count()===0,'Scans mixed results across databases')
  await screenshot(page,'source-scans')
  assert(errors.length===0,'Browser page errors')
  console.log('Real Source UI passed: create/save-test, no secret storage/echo, blank password edit, stop/start, port_source_test container_event (12 fields), another_source_test train_dispatch (3 fields), isolated scan results.')
}finally{
  await mkdir('.runtime',{recursive:true});await writeFile('.runtime/source-live-fixtures.json',JSON.stringify(created,null,2))
  await browser?.close();await server.close()
}
