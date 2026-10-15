import assert from 'node:assert/strict'
import {mkdir} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import {assertEvent,metricWindows,metricSummary,quantity} from '../src/utils/telemetry.js'
import {event,window} from './telemetry-fixtures.mjs'

process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
async function request(path){const response=await fetch(api+path),body=await response.json();assert.ok(response.ok);assert.equal(body.code,0);return body.data}
const history=await request('/runtime-events?collectionPoint=REST_PROVIDER&outcome=SUCCESS&limit=20')
assert.ok(history.total>0,'Run test:access-live first to generate real Provider observations')
const observed=assertEvent(history.rows[0]),from=new Date(Math.floor(Date.parse(observed.completed_at)/60000)*60000).toISOString(),until=new Date(Date.parse(from)+60000).toISOString()
const server=await createServer({server:{host:'127.0.0.1',port:5192,strictPort:true}})
let browser,page,release
await mkdir('.runtime',{recursive:true})
try{
  await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1800,height:1080},locale:'zh-CN',timezoneId:'Asia/Shanghai'})
  page.setDefaultTimeout(10000)
  const errors=[],writes=[];page.on('pageerror',e=>errors.push(e.message));page.on('request',req=>{if(req.url().includes('/api/v1/')&&req.method()!=='GET')writes.push(req.method())})
  async function ready(){await page.waitForFunction(()=>!document.querySelector('.el-loading-mask')&&!document.querySelector('.el-button.is-loading'))}
  async function navigate(path,title){await page.evaluate(path=>{history.pushState({},'',path);dispatchEvent(new PopStateEvent('popstate'))},path);await page.getByRole('heading',{name:title,exact:true}).waitFor();await ready()}
  async function select(label,option){await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:label,exact:true})}).click();await page.getByRole('option',{name:option,exact:true}).click()}
  async function query(){await page.getByRole('button',{name:'查询',exact:true}).click();await ready()}
  async function screenshot(name){if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'});await page.screenshot({path:'.runtime/'+name+'.png',fullPage:true,animations:'disabled'})}
  function localMinute(value){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value))}
  async function setRange(start,end){const inputs=page.locator('.el-date-editor--datetimerange input');await inputs.nth(0).fill(localMinute(start));await inputs.nth(1).fill(localMinute(end));await inputs.nth(1).press('Tab')}
  const table=()=>page.getByTestId('audit-table'),drawer=()=>page.getByRole('dialog',{name:'调用事件详情',exact:true})
  await page.goto('http://127.0.0.1:5192/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click();await ready()
  await page.getByRole('menuitem',{name:'运行监管',exact:true}).click();await page.getByRole('menuitem',{name:'调用审计',exact:true}).click();await page.getByRole('heading',{name:'调用审计',exact:true}).waitFor();await ready()
  await page.getByLabel('追踪编号筛选',{exact:true}).fill(observed.trace_id);await query()
  assert.equal(await table().getByRole('row').count(),2)
  await table().getByRole('button',{name:'详情',exact:true}).click();await ready();await drawer().getByTestId('runtime-event-detail').waitFor()
  const text=await drawer().textContent();assert.ok(text.includes(observed.policy_id));assert.ok(text.includes('关系 '+observed.relationship_version+' · 策略 '+observed.policy_version));assert.ok(!text.includes('GZPU'))
  await drawer().locator('.el-drawer__close-btn').click();await drawer().waitFor({state:'hidden'});await screenshot('telemetry-audit-real')
  await table().getByRole('button',{name:'追踪',exact:true}).click();await page.waitForURL('**/runtime-traces/'+observed.trace_id);await ready()
  assert.equal(await page.getByTestId('runtime-event-detail').count(),1);assert.ok((await page.getByTestId('trace-timeline').textContent()).includes(observed.resource_id));await screenshot('telemetry-trace-real')
  await navigate('/metrics','共享计量');await select('筛选对象','共享关系');await page.getByLabel('关联编号',{exact:true}).fill(observed.relationship_id);await setRange(from,until);await query()
  const params={collectionPoint:'REST_PROVIDER',relationshipId:observed.relationship_id,from,until},real=metricWindows(await request('/metrics/windows?'+new URLSearchParams(params)),params)
  for(const card of metricSummary(real,'REST_PROVIDER'))assert.equal((await page.getByTestId('metric-'+card.id).locator('.stat-number').textContent()).trim(),(card.partial?'≥ ':'')+quantity(card.value))
  assert.equal(await page.getByTestId('metric-table').getByRole('row').count(),2);await page.getByTestId('collector-status').waitFor();await screenshot('telemetry-metrics-real')
  await select('采集位置','消息生产');await query();assert.equal((await page.getByTestId('metric-traffic').locator('.stat-number').textContent()).trim(),'—');assert.ok((await page.getByTestId('metric-table').textContent()).includes('未知'))

  // Read-only HTTP fixtures verify failures and UI isolation; they are never production fallback data.
  const records=Array.from({length:45},(_,i)=>event(i+1));let metricMode='closed',collectorFault=false,listFault=false,listWrong=false,detailWrong=false,traceWrong=false,holdList=false,holdDetail=false
  const collector={enabled:true,storage_ready:true,queued_events:0,staged_reports:0,pending_reports:2,quarantined_reports:0,submitted_events:10,dropped_events:0,spool_failures:0,delivery_failures:1,rejected_reports:0,delivered_reports:8,last_success_at:null,last_failure_code:'DELIVERY_UNCONFIRMED'}
  const fulfill=(route,data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10001,message:'测试响应',data})})
  await page.route('**/api/v1/**',async route=>{
    assert.equal(route.request().method(),'GET');const url=new URL(route.request().url()),path=url.pathname.replace(/^\/provider\//,'/')
    if(path==='/api/v1/provider/telemetry')return fulfill(route,collectorFault?null:collector,collectorFault?503:200)
    if(path==='/api/v1/runtime-events'){
      const limit=Number(url.searchParams.get('limit')),offset=Number(url.searchParams.get('offset'))
      if(holdList){holdList=false;await new Promise(resolve=>{release=resolve})}
      if(listFault)return fulfill(route,null,503)
      const trace=url.searchParams.get('traceId'),rows=trace?records.filter(r=>r.trace_id===trace):records
      return fulfill(route,{total:rows.length,rows:rows.slice(offset,offset+limit).map(r=>listWrong?{...r,token:'MUST_NOT_RENDER'}:r),limit,offset})
    }
    if(path.startsWith('/api/v1/runtime-events/')){
      const id=path.split('/').at(-1),row=records.find(r=>r.event_id===id)
      if(holdDetail){holdDetail=false;await new Promise(resolve=>{release=resolve})}
      return fulfill(route,detailWrong?event(999):row)
    }
    if(path.startsWith('/api/v1/runtime-traces/')){const id=path.split('/').at(-1);return fulfill(route,traceWrong?[event(999)]:records.filter(r=>r.trace_id===id))}
    if(path==='/api/v1/metrics/windows'){
      if(metricMode==='fault')return fulfill(route,null,503)
      const start=Date.parse(url.searchParams.get('from')),end=Date.parse(url.searchParams.get('until')),point=url.searchParams.get('collectionPoint'),data=[]
      for(let t=start;t<end;t+=60000){const state=metricMode==='unknown'?'UNKNOWN':metricMode==='missing'?'MISSING':metricMode==='partial'?'PARTIAL':'CLOSED'
        const changes=metricMode==='partial'?{attempts:4,calls:point==='REST_PROVIDER'?4:null,messages:point==='KAFKA_PRODUCER'?3:null,successes:3,denied:1,records:3,bytes:600,average_duration_ms:20,frequency_per_second:(point==='REST_PROVIDER'?4:3)/60}:{}
        data.push(window(new Date(t).toISOString(),state,point,changes))}
      return fulfill(route,data)
    }
    return route.continue()
  })
  await navigate('/runtime-events','调用审计');assert.equal(await table().getByRole('row').count(),21)
  await page.getByRole('button',{name:'下一页',exact:true}).click();await ready();assert.ok((await table().getByRole('row').nth(1).textContent()).includes(records[20].resource_id))
  await page.getByLabel('追踪编号筛选',{exact:true}).fill(records[44].trace_id);await query();assert.equal(await table().getByRole('row').count(),2)
  detailWrong=true;await table().getByRole('button',{name:'详情',exact:true}).click();await ready();await drawer().getByText('事件详情读取失败',{exact:true}).waitFor();assert.equal(await drawer().getByTestId('runtime-event-detail').count(),0)
  detailWrong=false;await drawer().getByRole('button',{name:'重试详情',exact:true}).click();await ready();assert.ok((await drawer().getByTestId('runtime-event-detail').textContent()).includes(records[44].event_id))
  await drawer().locator('.el-drawer__close-btn').click();await drawer().waitFor({state:'hidden'})
  listFault=true;await query();await page.getByTestId('audit-error').waitFor();assert.equal(await table().count(),0)
  listFault=false;listWrong=true;await query();await page.getByTestId('audit-error').waitFor();assert.equal(await table().count(),0);assert.ok(!(await page.locator('body').textContent()).includes('MUST_NOT_RENDER'))
  listWrong=false;await query();holdDetail=true;await table().getByRole('button',{name:'详情',exact:true}).click()
  let deadline=Date.now()+10000;while(!release){assert.ok(Date.now()<deadline);await page.waitForTimeout(20)}
  await drawer().locator('.el-drawer__close-btn').click();await drawer().waitFor({state:'hidden'});release();release=null;await ready();await table().getByRole('button',{name:'详情',exact:true}).click();await ready();assert.ok((await drawer().getByTestId('runtime-event-detail').textContent()).includes(records[44].event_id))
  await drawer().locator('.el-drawer__close-btn').click();await drawer().waitFor({state:'hidden'})
  traceWrong=true;await table().getByRole('button',{name:'追踪',exact:true}).click();await ready();await page.getByTestId('trace-error').waitFor();assert.equal(await page.getByTestId('trace-timeline').count(),0)
  traceWrong=false;await page.getByRole('button',{name:'查询追踪',exact:true}).click();await ready();assert.equal(await page.getByTestId('runtime-event-detail').count(),1)
  await navigate('/metrics','共享计量');await setRange(from,until);await query()
  assert.equal((await page.getByTestId('metric-traffic').locator('.stat-number').textContent()).trim(),'0')
  metricMode='missing';await query();assert.equal((await page.getByTestId('metric-traffic').locator('.stat-number').textContent()).trim(),'—')
  metricMode='partial';await query();assert.equal((await page.getByTestId('metric-traffic').locator('.stat-number').textContent()).trim(),'≥ 4')
  await select('采集位置','消息生产');await query();assert.equal((await page.getByTestId('metric-traffic').locator('.stat-number').textContent()).trim(),'≥ 3')
  collectorFault=true;await page.getByRole('button',{name:'刷新采集状态',exact:true}).click();await ready();await page.getByTestId('collector-error').waitFor();assert.equal((await page.getByTestId('metric-traffic').locator('.stat-number').textContent()).trim(),'≥ 3')
  metricMode='fault';await query();await page.getByTestId('metric-error').waitFor();assert.equal((await page.getByTestId('metric-traffic').locator('.stat-number').textContent()).trim(),'—');assert.equal(await page.getByTestId('metric-table').getByRole('row').count(),1)
  metricMode='unknown';collectorFault=false;await query();await page.getByRole('button',{name:'刷新采集状态',exact:true}).click();await ready();await page.getByTestId('collector-status').waitFor();assert.equal((await page.getByTestId('metric-traffic').locator('.stat-number').textContent()).trim(),'—')
  await navigate('/runtime-events','调用审计');holdList=true;await page.getByRole('button',{name:'刷新记录',exact:true}).click()
  deadline=Date.now()+10000;while(!release){assert.ok(Date.now()<deadline);await page.waitForTimeout(20)}
  await navigate('/about','系统说明');release();release=null;await ready();assert.equal(await table().count(),0)
  assert.deepEqual(writes,[]);assert.deepEqual(errors,[])
  console.log('Telemetry UI passed: real automatic observations and governance versions; trace navigation; real minute counts; server paging/filtering; foreign/secret response rejection; detail retry and closed-drawer isolation; unknown/zero/lower-bound distinction; separate REST/Kafka units; independent collector faults; late response isolation. GET only; no business records or database writes.')
}catch(error){await page?.screenshot({path:'.runtime/telemetry-ui-failure.png',fullPage:true,animations:'disabled'}).catch(()=>{});throw error}
finally{release?.();await browser?.close();await server.close()}
