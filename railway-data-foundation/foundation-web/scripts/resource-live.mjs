import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { mkdir, writeFile } from 'node:fs/promises'

// Local opt-in validation. All temporary Source/Resource/Capability names use the guarded UI_REST_ prefix.
for (const name of ['PROVIDER_SOURCE_DB_URL','PROVIDER_SOURCE_DB_USER','PROVIDER_SOURCE_DB_PASSWORD'])
  if (!process.env[name]) throw new Error('Missing ' + name)
const uri = new URL(process.env.PROVIDER_SOURCE_DB_URL.slice(5))
if (uri.hostname !== '127.0.0.1' || uri.pathname !== '/port_source_db') throw new Error('Only the local demo Source is allowed')
process.env.VITE_USE_MOCK = 'false'
const api = 'http://127.0.0.1:18080/api/v1', run = Date.now().toString(36), fixtures = []
const server = await createServer({ server:{ host:'127.0.0.1', port:5177, strictPort:true } })
let browser, page, unblockLateFields
function assert(condition, message) { if (!condition) throw new Error(message) }
async function data(response) {
  assert(response.ok(), 'Unexpected API HTTP ' + response.status())
  const json = await response.json()
  assert(json.code === 0, 'API operation failed')
  return json.data
}
async function screenshot(page, name) {
  if (await page.locator('.el-message').count()) await page.locator('.el-message').last().waitFor({state:'hidden'})
  await page.screenshot({path:'.runtime/'+name+'.png', fullPage:true, animations:'disabled'})
}
try {
  await server.listen()
  browser = await chromium.launch({ executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless:true })
  page = await browser.newPage({viewport:{width:1660,height:1050},locale:'zh-CN'})
  const errors = []
  page.on('pageerror', error=>errors.push(error.message))
  const source = await data(await page.request.post(api+'/sources',{data:{source_name:'UI_REST_资源验收来源_'+run,source_type:'MYSQL',
    host:uri.hostname,port:Number(uri.port),database_name:uri.pathname.slice(1),username:process.env.PROVIDER_SOURCE_DB_USER,
    password:process.env.PROVIDER_SOURCE_DB_PASSWORD,ssl_mode:'DISABLED'}}))
  fixtures.push({source_id:source.source_id,source_name:source.source_name,credential_ref:source.credential_ref})
  // Persist cleanup identity before any UI wait, including failures that terminate Node early.
  await mkdir('.runtime',{recursive:true})
  await writeFile('.runtime/resource-live-fixtures.json',JSON.stringify(fixtures,null,2))
  const scan = await data(await page.request.post(api+'/sources/'+source.source_id+'/scan'))
  assert(scan.status==='SUCCESS' && scan.field_count===12,'Real scan failed')
  const secondScan = await data(await page.request.post(api+'/sources/'+source.source_id+'/scan'))
  await page.goto('http://127.0.0.1:5177/',{waitUntil:'networkidle'})
  await page.getByRole('button',{name:'进入平台'}).click()
  await page.goto('http://127.0.0.1:5177/resources',{waitUntil:'networkidle'})
  await page.getByRole('button',{name:'从扫描结果创建资源'}).click()
  await page.waitForURL('**/scans')
  assert(new URL(page.url()).pathname==='/scans','Resource creation entry navigates to the wrong page')
  await page.goto('http://127.0.0.1:5177/scans?task='+scan.task_id,{waitUntil:'networkidle'})
  const fieldsUrl = '**/scan-tasks/'+scan.task_id+'/tables/container_event/fields'
  let release, started
  const gate = new Promise(resolve=>{release=resolve}), intercepted = new Promise(resolve=>{started=resolve})
  unblockLateFields = release
  await page.route(fieldsUrl,async route=>{started();await gate;await route.continue()})
  await page.getByRole('button',{name:'查看字段',exact:true}).first().click()
  await intercepted
  // The drawer now shows loading immediately; close it before switching tasks.
  await page.getByRole('dialog').locator('.el-drawer__close-btn').click()
  await page.getByRole('dialog').waitFor({state:'hidden'})
  const selectedResponse = page.waitForResponse(r=>r.url().endsWith('/scan-tasks/'+secondScan.task_id+'/tables'))
  await page.locator('[data-task-id="'+secondScan.task_id+'"]').getByRole('button',{name:'查看扫描结果'}).click()
  await selectedResponse
  const lateResponse = page.waitForResponse(r=>r.url().endsWith('/scan-tasks/'+scan.task_id+'/tables/container_event/fields'))
  release(); await lateResponse
  await page.waitForTimeout(200)
  assert(await page.getByRole('dialog').count()===0,'Late field result reopened the old task drawer')
  await page.unroute(fieldsUrl)
  await page.getByRole('button',{name:'创建资源',exact:true}).first().click()
  const dialog = page.getByRole('dialog')
  for (const label of ['来源系统','数据库模式','物理表'])
    assert(await dialog.getByLabel(label,{exact:true}).getAttribute('readonly')!==null,'Scanned origin can be edited: '+label)
  const resourceName = 'UI_REST_资源闭环_'+run
  await dialog.getByLabel('资源名称').fill(resourceName)
  await dialog.getByLabel('责任单位').fill('资源验收组')
  await dialog.getByLabel('业务描述').fill('从真实扫描登记的临时资源，仅用于资源页面验收')
  const storageSnapshot = () => page.evaluate(()=>JSON.stringify(Object.entries(localStorage).sort()))
  const initialStorage = await storageSnapshot()
  const createUrl = '**/api/v1/resources'
  await page.route(createUrl,route=>route.request().method()==='POST'?route.abort('failed'):route.continue())
  await dialog.getByRole('button',{name:'保存资源'}).click()
  await page.getByText(/保存资源请求失败/).waitFor()
  assert(await dialog.isVisible() && await dialog.getByLabel('资源名称').inputValue()===resourceName,'Failed creation lost input')
  assert((await data(await page.request.get(api+'/resources?limit=200'))).filter(r=>r.source_id===source.source_id).length===0,'Failed creation reached backend')
  assert(await storageSnapshot()===initialStorage,'Failed real creation wrote mock data')
  await page.unroute(createUrl)
  const creation = page.waitForResponse(r=>r.url().endsWith('/api/v1/resources') && r.request().method()==='POST')
  await dialog.getByRole('button',{name:'保存资源'}).click()
  const resource = await data(await creation), id = resource.resource_id
  fixtures[0].resource_id = id
  await page.getByRole('heading',{name:resourceName}).waitFor()
  const current = async () => data(await page.request.get(api+'/resources/'+id))
  const fieldRows = async () => data(await page.request.get(api+'/resources/'+id+'/fields'))
  await page.getByRole('tab',{name:/接口能力/}).click()
  assert(await page.getByRole('button',{name:/注册能力$/}).isDisabled(),'Draft resource allows capability registration')
  const fieldReadUrl = '**/resources/'+id+'/fields'
  await page.route(fieldReadUrl,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10006,message:'测试字段读取不可用',data:null})}))
  await page.getByRole('button',{name:'刷新详情',exact:true}).click()
  await page.getByRole('tab',{name:/字段元数据/}).click()
  await page.getByText(/字段元数据加载失败/).waitFor()
  assert(await page.getByText('container_no',{exact:true}).count()===0,'Real field read failure displayed demo fields')
  await page.unroute(fieldReadUrl)
  await page.getByRole('button',{name:'刷新详情',exact:true}).click()
  await page.getByText('event_time',{exact:true}).waitFor()
  const eventRow = page.getByRole('row').filter({hasText:'event_time'})
  const versionBeforeField = (await current()).version
  await eventRow.getByRole('button',{name:'编辑',exact:true}).click()
  const fieldDialog = page.getByRole('dialog')
  await fieldDialog.getByLabel('业务名称').fill('真实作业发生时间')
  await fieldDialog.getByLabel('业务描述').fill('实际业务发生时刻，人工补充的字段属性')
  if (await fieldDialog.getByRole('switch').getAttribute('aria-checked')!=='true') await fieldDialog.locator('.el-switch').click()
  const fieldWriteUrl = '**/resources/'+id+'/fields/event_time'
  await page.route(fieldWriteUrl,route=>route.abort('timedout'))
  await fieldDialog.getByRole('button',{name:'保存字段'}).click()
  await page.getByText(/保存字段请求(超时|失败)/).waitFor()
  assert(await fieldDialog.isVisible() && (await current()).version===versionBeforeField,'Failed field edit changed resource')
  assert(await storageSnapshot()===initialStorage,'Failed real field edit wrote mock data')
  await page.unroute(fieldWriteUrl)
  let committed
  const commit = new Promise(resolve=>{committed=resolve})
  await page.route(fieldWriteUrl,async route=>{const response=await route.fetch();assert(response.ok(),'Lost-response field update failed');await route.abort('failed');committed()})
  await fieldDialog.getByRole('button',{name:'保存字段'}).click()
  await commit
  await page.waitForFunction(()=>!document.querySelector('[role="dialog"] .is-loading'))
  assert(await fieldDialog.isVisible(),'Lost field response was presented as success')
  assert((await current()).version===versionBeforeField+1,'Lost response caused an automatic duplicate field write')
  assert(await storageSnapshot()===initialStorage,'Lost real response wrote mock data')
  await page.unroute(fieldWriteUrl)
  await fieldDialog.getByRole('button',{name:'取消',exact:true}).click()
  await page.getByRole('button',{name:'刷新详情',exact:true}).click()
  await page.getByText('真实作业发生时间',{exact:true}).waitFor()
  const savedField = (await fieldRows()).find(f=>f.field_name==='event_time')
  assert(savedField.time_field && savedField.business_description.includes('人工补充'),'Field changes were not actually persisted')
  await eventRow.getByRole('button',{name:'编辑',exact:true}).click()
  await fieldDialog.getByLabel('业务名称').fill('作业事件时间')
  await fieldDialog.getByRole('button',{name:'保存字段'}).click()
  await page.getByText('作业事件时间',{exact:true}).waitFor()
  await page.getByRole('button',{name:'返回资源目录'}).click()
  const resourceRow = page.getByRole('row').filter({hasText:resourceName})
  await resourceRow.getByRole('button',{name:'编辑',exact:true}).click()
  assert(await dialog.getByLabel('物理表').getAttribute('readonly')!==null,'Resource edit can change physical table')
  await dialog.getByLabel('责任单位').fill('资源验收组（修订）')
  const updateUrl = '**/api/v1/resources/'+id
  await page.route(updateUrl,route=>route.request().method()==='PUT'?route.abort('failed'):route.continue())
  await dialog.getByRole('button',{name:'保存资源'}).click()
  await page.getByText(/保存资源请求失败/).last().waitFor()
  assert((await current()).owner_department==='资源验收组','Failed resource edit persisted changes')
  await page.unroute(updateUrl)
  await dialog.getByRole('button',{name:'保存资源'}).click()
  await page.getByRole('heading',{name:resourceName}).waitFor()
  assert((await current()).owner_department==='资源验收组（修订）','Real resource edit did not persist')
  const publishUrl = '**/api/v1/resources/'+id+'/publish', versionBeforePublish = (await current()).version
  await page.route(publishUrl,route=>route.abort('failed'))
  await page.getByRole('button',{name:'发布资源',exact:true}).click()
  await page.getByText(/发布资源请求失败/).waitFor()
  assert((await current()).status==='DRAFT' && (await current()).version===versionBeforePublish,'Failed publication changed resource')
  assert(await storageSnapshot()===initialStorage,'Real failure wrote browser mock state')
  await page.unroute(publishUrl)
  await page.getByRole('button',{name:'发布资源',exact:true}).click()
  await page.getByText('已发布',{exact:true}).first().waitFor()
  assert((await current()).status==='PUBLISHED' && (await current()).version===versionBeforePublish+1,'Publication was not persisted')
  await page.getByRole('tab',{name:/字段元数据/}).click()
  await page.getByText('作业事件时间',{exact:true}).waitFor()
  await mkdir('.runtime',{recursive:true})
  await screenshot(page,'resource-live-fields')
  await page.getByRole('tab',{name:'基本信息',exact:true}).click()
  await screenshot(page,'resource-live-detail')
  await page.getByRole('tab',{name:/接口能力/}).click()
  await page.getByRole('button',{name:/注册能力$/}).click()
  await dialog.getByLabel('能力名称',{exact:true}).fill('UI_REST_资源验收接口_'+run)
  await dialog.getByLabel('接口路径',{exact:true}).fill('/containers/events')
  const capCreation = page.waitForResponse(r=>r.url().endsWith('/api/v1/capabilities') && r.request().method()==='POST')
  await dialog.getByRole('button',{name:'保存能力'}).click()
  fixtures[0].capability_id = (await data(await capCreation)).capability_id
  assert(errors.length===0,'Browser errors: '+errors.join(' | '))
  assert(await storageSnapshot()===initialStorage,'Real workflow changed demo storage')
  console.log('Real resource UI passed: scanned origin locked, stale field result ignored, real failures never mocked, lost response not retried, resource/field edits persisted, publish then register capability.')
} catch (error) {
  await mkdir('.runtime',{recursive:true})
  if (page) await page.screenshot({path:'.runtime/resource-live-failure.png',fullPage:true,animations:'disabled'})
  throw error
} finally {
  unblockLateFields?.()
  await mkdir('.runtime',{recursive:true})
  await writeFile('.runtime/resource-live-fixtures.json',JSON.stringify(fixtures,null,2))
  await browser?.close()
  await server.close()
}
