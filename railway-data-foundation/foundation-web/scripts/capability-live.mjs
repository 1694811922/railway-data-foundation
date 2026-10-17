import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { mkdir, readFile, writeFile } from 'node:fs/promises'

// Opt-in local check. Uses guarded UI_SHARE_ metadata and real sharing credentials, never user records.
// No secrets are written to the fixture manifest or screenshots.
for (const name of ['PROVIDER_SOURCE_DB_URL', 'PROVIDER_SOURCE_DB_USER', 'PROVIDER_SOURCE_DB_PASSWORD'])
  if (!process.env[name]) throw new Error('Missing ' + name)
process.env.VITE_USE_MOCK = 'false'
const api = 'http://127.0.0.1:18080/api/v1'
const provider = 'http://127.0.0.1:18081'
const run = crypto.randomUUID().replaceAll('-','').toUpperCase()
await mkdir('.runtime', {recursive:true})
const manifest='.runtime/sharing-live-fixtures.json'
let created=[];try{created=JSON.parse(await readFile(manifest,'utf8'))}catch(e){if(e.code!=='ENOENT')throw e}
const fixture={run,source_name:'UI_SHARE_source_'+run,provider_code:'UI_SHARE_PROVIDER_'+run,demand_code:'UI_SHARE_DEMAND_'+run}
created.push(fixture)
const record=()=>writeFile(manifest,JSON.stringify(created,null,2))
await record()
const uri = new URL(process.env.PROVIDER_SOURCE_DB_URL.slice(5))
if (uri.hostname !== '127.0.0.1' || uri.pathname !== '/port_source_db') throw new Error('Live check is restricted to the local demo Source')
const server = await createServer({ server: { host: '127.0.0.1', port: 5176, strictPort: true } })
let browser,callerToken=''
function assert(condition, message) { if (!condition) throw new Error(message) }
async function data(response) {
  assert(response.ok(), 'Unexpected API HTTP ' + response.status())
  const json = await response.json()
  assert(json.code === 0, 'API operation failed')
  return json.data
}
async function screenshot(page, name) {
  if (await page.locator('.el-message').count()) await page.locator('.el-message').last().waitFor({ state: 'hidden' })
  await page.screenshot({ path: '.runtime/' + name + '.png', fullPage: true, animations: 'disabled' })
}
try {
  await server.listen()
  browser = await chromium.launch({ executablePath: process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true })
  const page = await browser.newPage({ viewport: { width: 1660, height: 1050 }, locale: 'zh-CN', permissions: ['clipboard-read', 'clipboard-write'] })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  const source = await data(await page.request.post(api + '/sources', { data: {
    source_name: fixture.source_name, source_type: 'MYSQL', host: uri.hostname, port: Number(uri.port), database_name: uri.pathname.slice(1),
    username: process.env.PROVIDER_SOURCE_DB_USER, password: process.env.PROVIDER_SOURCE_DB_PASSWORD, ssl_mode: 'DISABLED',
  } }))
  Object.assign(fixture,{source_id:source.source_id,credential_ref:source.credential_ref});await record()
  const scan = await data(await page.request.post(api + '/sources/' + source.source_id + '/scan'))
  assert(scan.status === 'SUCCESS' && scan.field_count === 12, 'Source metadata scan did not succeed')
  const resource = await data(await page.request.post(api + '/resources', { data: {
    resource_name: 'UI_SHARE_resource_' + run, source_id: source.source_id, scan_task_id: scan.task_id, schema_name: uri.pathname.slice(1), physical_table: 'container_event',
    business_description: 'REST启停专项验证使用的临时元数据', owner_department: '本机测试', security_level: '内部', update_mode: '实时',
  } }))
  fixture.resource_id = resource.resource_id;await record()
  await data(await page.request.put(api+'/resources/'+resource.resource_id+'/fields/event_time',{data:{business_name:'事件时间',business_description:'共享时间范围',time_field:true,sensitivity_level:'内部',resource_version:resource.version}}))
  await data(await page.request.post(api + '/resources/' + resource.resource_id + '/publish',{data:{version:resource.version,reason:'独立测试发布'}}))
  await page.goto('http://127.0.0.1:5176/', { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '进入平台' }).click()
  await page.goto('http://127.0.0.1:5176/capabilities?resource=' + resource.resource_id + '&create=1', { waitUntil: 'networkidle' })
  assert(await page.getByTestId('demo-notice').count() === 0, 'Unexpected demo fallback')
  const dialog = page.getByRole('dialog')
  const name = 'UI_SHARE_capability_' + run
  await dialog.getByLabel('能力名称', { exact: true }).fill(name)
  await dialog.getByLabel('接口路径', { exact: true }).fill('/containers/events')
  await mkdir('.runtime', { recursive: true })
  await screenshot(page, 'capability-registration-help')
  const creation = page.waitForResponse(r => r.url().endsWith('/api/v1/capabilities') && r.request().method() === 'POST')
  await dialog.getByRole('button', { name: '保存能力' }).click()
  const capability = await data(await creation)
  fixture.capability_id = capability.capability_id;await record()
  const id = capability.capability_id
  const row = page.getByRole('row').filter({ hasText: name })
  assert(await row.getByRole('button', { name: '启用', exact: true }).isDisabled(), 'Draft capability can enable')
  for(const role of ['PROVIDER','DEMAND']){
    const subject=await data(await page.request.post(api+'/subjects',{data:{subject_code:fixture[role.toLowerCase()+'_code'],subject_name:'UI_SHARE_'+role+'_'+run,subject_type:role,owner_department:'本机测试',description:'能力回归临时主体'}}))
    fixture[role.toLowerCase()+'_id']=subject.subject_id;await record()
  }
  let relation=await data(await page.request.post(api+'/relationships',{data:{relationship_name:'UI_SHARE_relationship_'+run,provider_subject_id:fixture.provider_id,demand_subject_id:fixture.demand_id,resource_id:fixture.resource_id,capability_id:id,purpose:'保留REST启停回归',reason:'真实回归测试',valid_from:new Date(Date.now()-60000).toISOString(),valid_until:new Date(Date.now()+3600000).toISOString(),policy:{allowed_fields:['container_no','event_time'],time_field:'event_time',data_from:'2026-09-01T00:00:00Z',data_until:'2026-10-01T00:00:00Z'}}}))
  fixture.relationship_id=relation.relationship_id;fixture.policy_id=relation.policy.policy_id;await record()
  for(const action of ['submit','confirm'])relation=await data(await page.request.post(api+'/relationships/'+relation.relationship_id+'/'+action,{data:{version:relation.version,reason:'能力回归'+action}}))
  const issued=await data(await page.request.post(api+'/subjects/'+fixture.demand_id+'/credentials',{data:{credential_name:'UI_SHARE_credential_'+run,expires_at:new Date(Date.now()+3600000).toISOString(),reason:'能力启停回归'}}))
  callerToken=issued.token;fixture.caller_credential_id=issued.credential.credential_id;await record()
  const query = () => page.request.get(provider + '/containers/events?limit=1', { headers: { 'X-Capability-Id': id,'X-Relationship-Id':fixture.relationship_id,Authorization:'Bearer '+callerToken } })
  assert((await query()).status() === 403, 'Draft capability returns business rows')
  assert((await page.request.get(provider + '/containers/events?limit=1')).status() === 400, 'Missing capability header accepted')
  await row.getByRole('button', { name: '验证', exact: true }).click()
  await row.getByText('已验证', { exact: true }).waitFor()
  await row.getByRole('button', { name: '启用', exact: true }).click()
  await row.getByText('已启用', { exact: true }).waitFor()
  relation=await data(await page.request.post(api+'/relationships/'+relation.relationship_id+'/enable',{data:{version:relation.version,reason:'启用回归关系'}}))
  assert((await data(await query())).length === 1, 'Enabled Provider query failed')
  await mkdir('.runtime', { recursive: true })
  await screenshot(page, 'rest-capability-enabled')
  await row.getByRole('button', { name: '查看', exact: true }).click()
  await page.getByText('接口能力可用', { exact: true }).waitFor()
  await page.getByText('X-Capability-Id: ' + id, { exact: true }).waitFor()
  await page.getByText('GET ' + provider + '/containers/events', { exact: true }).waitFor()
  const guide = await data(await page.request.get(api + '/capabilities/' + id + '/invocation'))
  assert(guide.enabled && guide.request_url === provider + '/containers/events' && guide.header_value === id, 'Incorrect real calling guide')
  await page.getByRole('button', { name: '复制调用命令', exact: true }).click()
  const copied = await page.evaluate(() => navigator.clipboard.readText())
  assert(copied.includes(guide.request_url + '?limit=5') && copied.includes("'X-Capability-Id' = '" + id + "'"), 'Copied command omits real address or header')
  assert(copied.includes('X-Relationship-Id')&&copied.includes('Authorization')&&!copied.includes(callerToken),'Copied command must describe sharing credentials without embedding a real token')
  await screenshot(page, 'rest-capability-detail')
  await page.keyboard.press('Escape')
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  await row.getByRole('button', { name: '停用', exact: true }).click()
  await row.getByText('已停用', { exact: true }).waitFor()
  const stopped = await query()
  assert(stopped.status() === 403 && (await stopped.json()).data === null, 'Stopped capability still returns rows')
  await screenshot(page, 'rest-capability-disabled')
  await row.getByRole('button', { name: '验证', exact: true }).click()
  await row.getByText('已停用', { exact: true }).waitFor()
  await row.getByRole('button', { name: '启用', exact: true }).click()
  await row.getByText('已启用', { exact: true }).waitFor()
  const actionUrl = '**/capabilities/' + id + '/disable'
  await page.route(actionUrl, route => route.abort('timedout'))
  await row.getByRole('button', { name: '停用', exact: true }).click()
  await page.getByText(/接口能力操作(请求(失败|超时)|结果尚未确认)/).waitFor()
  await row.getByText('已启用', { exact: true }).waitFor()
  assert((await data(await query())).length === 1, 'Failed request became a mock stop')
  await page.unroute(actionUrl)
  // A failed read must not turn a real capability into demo calling instructions.
  await page.route('**/capabilities/' + id + '/invocation', route => route.abort('failed'))
  await row.getByRole('button', { name: '查看', exact: true }).click()
  await page.getByText(/调用信息加载失败/).waitFor()
  assert(await page.getByRole('button', { name: '复制调用命令' }).count() === 0, 'Failed real guide became a mock sample')
  await page.unroute('**/capabilities/' + id + '/invocation')
  await page.getByRole('button', { name: '刷新调用信息' }).click()
  await page.getByText('GET ' + provider + '/containers/events', { exact: true }).waitFor()
  await page.keyboard.press('Escape')
  await page.getByRole('dialog').waitFor({ state: 'hidden' })
  await data(await page.request.patch(api + '/sources/' + source.source_id + '/status', { data: { status: 'DISABLED' } }))
  assert((await query()).status() === 403, 'Stopped Source still returns business rows')
  await data(await page.request.patch(api + '/sources/' + source.source_id + '/status', { data: { status: 'ENABLED' } }))
  assert((await data(await query())).length === 1, 'Source restoration did not restore the enabled capability')
  await row.getByRole('button', { name: '停用', exact: true }).click()
  await row.getByText('已停用', { exact: true }).waitFor()
  assert(errors.length === 0, 'Browser page errors')
  console.log('Real REST UI passed: lifecycle 200/403/200, actual configured address/header, command copy, failed guide read/retry without mock fallback, stopped Source denied.')
} finally {
  await mkdir('.runtime', { recursive: true })
  callerToken='';await record()
  await browser?.close()
  await server.close()
}
