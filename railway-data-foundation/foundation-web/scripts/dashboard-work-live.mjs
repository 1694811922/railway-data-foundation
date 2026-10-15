import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { loadDashboard } from '../src/utils/dashboard.js'
import { dashboardWorkGroups,dashboardWork } from '../src/utils/dashboardWork.js'

process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1', endpoints={sources:'sources',resources:'resources',capabilities:'capabilities',tasks:'scan-tasks'}
const real=await loadDashboard(Object.fromEntries(Object.entries(endpoints).map(([key,path])=>[key,async params=>{
  const response=await fetch(api+'/'+path+'?'+new URLSearchParams(params)), body=await response.json()
  assert.ok(response.ok);assert.equal(body.code,0);return body.data
}])))
assert.equal(Object.values(real).some(data=>data.error),false,'Start Foundation before this test')
const server=await createServer({server:{host:'127.0.0.1',port:5186,strictPort:true}})
let browser,page,release
try {
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'})
  const errors=[],writes=[];page.on('pageerror',e=>errors.push(e.message))
  page.on('request',req=>{if(req.url().includes('/api/v1/')&&req.method()!=='GET')writes.push(req.method())})
  async function ready(){await page.getByRole('heading',{name:'首页概览',exact:true}).waitFor();await page.waitForFunction(()=>!document.querySelector('.dashboard .el-loading-mask'))}
  const work=()=>page.getByTestId('dashboard-work'), table=()=>page.getByTestId('dashboard-work-list')
  const tab=label=>work().getByRole('tab',{name:new RegExp('^'+label+'（')})
  async function refresh(){await page.getByRole('button',{name:'刷新数据',exact:true}).click();await ready()}
  async function home(){await page.evaluate(()=>{history.pushState({},'','/dashboard');dispatchEvent(new PopStateEvent('popstate'))});await ready()}
  await page.goto('http://127.0.0.1:5186/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click();await ready()
  for(const group of dashboardWorkGroups){
    const actual=dashboardWork(real,group.key)
    assert.equal(await tab(group.label).textContent(),group.label+'（'+actual.count+'）')
  }
  await mkdir('.runtime',{recursive:true});await page.screenshot({path:'.runtime/dashboard-work-real.png',fullPage:true})
  const resources=Array.from({length:26},(_,i)=>({resource_id:'RES_WORK_'+i,resource_name:'港口待办资源 '+i,source_id:'SRC_WORK',source_name:'港口来源',status:i===25?'PUBLISHED':'DRAFT',version:1,created_at:'2026-10-03T09:00:00',updated_at:i===24?'2026-10-04T09:00:00':'2026-10-03T09:00:00'}))
  const capabilities=Array.from({length:24},(_,i)=>({capability_id:'CAP_WORK_'+i,capability_name:'草稿能力 '+i,resource_id:'RES_WORK_25',resource_name:'港口已发布资源',status:'DRAFT',health_status:'UNKNOWN',type:i===0?'CDC_STREAM':'REST_API',endpoint:'/containers/events',http_method:'GET',auth_type:'NONE',version:1,updated_at:'2026-10-04T09:00:00'}))
  const tasks=Array.from({length:12},(_,i)=>({task_id:'SCAN_WORK_'+i,source_id:'SRC_WORK',source_name:'权限检查来源 '+i,status:'FAILED',table_count:0,field_count:0,started_at:'2026-10-04T09:00:00',finished_at:'2026-10-04T09:01:00',failure_reason:'读取权限不足 '+i}))
  const fixture={resources,capabilities,tasks,sources:[{source_id:'SRC_WORK',source_name:'港口来源',status:'ENABLED'}]}
  let fault='',hold=false,bounded=false
  await page.route('**/api/v1/**',async route=>{
    assert.equal(route.request().method(),'GET','Read-only overview must never send writes')
    const url=new URL(route.request().url()), parts=url.pathname.split('/'), key=Object.keys(endpoints).find(key=>endpoints[key]===parts[3])
    const fulfill=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10001,message:'测试响应',data})})
    if(parts[4]) {
      if(key==='resources')return fulfill(parts[5]?[]:resources.find(row=>row.resource_id===parts[4]))
      if(key==='tasks')return fulfill(parts[5]?[]:tasks.find(row=>row.task_id===parts[4]))
      throw new Error('Unexpected detail API')
    }
    assert.ok(key)
    if(hold&&key==='resources'){hold=false;await new Promise(resolve=>{release=resolve})}
    if(fault===key)return fulfill(null,503)
    const rows=bounded&&key==='resources'?Array.from({length:5001},(_,i)=>({...resources[0],resource_id:'RES_BOUND_'+i})):fixture[key]
    const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'))
    return fulfill(rows.slice(offset,offset+limit))
  })
  await refresh();assert.equal(await tab('待发布资源').textContent(),'待发布资源（25）')
  assert.equal(await table().getByRole('row').count(),11)
  assert.match(await table().getByRole('row').nth(1).textContent(),/港口待办资源 24/)
  await page.getByTestId('dashboard-work-pager').getByRole('button',{name:'下一页',exact:true}).click()
  await page.getByLabel('待处理事项关键词',{exact:true}).fill('RES_WORK_24 港口')
  assert.equal(await table().getByRole('row').count(),2)
  await table().getByRole('button',{name:'查看资源',exact:true}).click()
  await page.waitForURL('**/resources/RES_WORK_24');await page.getByRole('heading',{name:'港口待办资源 24',exact:true}).waitFor()
  await home();await tab('待验证能力').click();assert.equal(await tab('待验证能力').textContent(),'待验证能力（24）')
  await page.getByLabel('待处理事项关键词',{exact:true}).fill('CAP_WORK_0')
  assert.match(await table().textContent(),/当前只支持指定REST适配器和无认证Kafka主题验证/)
  await table().getByRole('button',{name:'管理资源能力',exact:true}).click()
  await page.waitForURL('**/capabilities?resource=RES_WORK_25');await page.getByRole('heading',{name:'接口能力管理',exact:true}).waitFor()
  await home();await tab('失败扫描').click();assert.equal(await tab('失败扫描').textContent(),'失败扫描（12）')
  await page.getByLabel('待处理事项关键词',{exact:true}).fill('SCAN_WORK_3 权限')
  await table().getByRole('button',{name:'查看失败任务',exact:true}).click();await page.waitForURL('**/scans?task=SCAN_WORK_3')
  await page.locator('.selected-summary').waitFor();assert.match(await page.locator('.selected-summary').textContent(),/SCAN_WORK_3/)
  await home();await page.screenshot({path:'.runtime/dashboard-work-items.png',fullPage:true})
  fault='resources';await refresh();await page.getByTestId('dashboard-work-error').waitFor()
  assert.equal(await tab('待发布资源').textContent(),'待发布资源（—）');assert.equal(await table().count(),0)
  await tab('失败扫描').click();assert.equal(await table().getByRole('row').count(),11)
  fault='';await refresh();await tab('待发布资源').click();assert.equal(await tab('待发布资源').textContent(),'待发布资源（25）')
  const oldStatus=resources[24].status;resources[24].status='PUBLISHED';await refresh();assert.equal(await tab('待发布资源').textContent(),'待发布资源（24）')
  await page.getByLabel('待处理事项关键词',{exact:true}).fill('RES_WORK_24');assert.equal(await table().getByRole('row').count(),1)
  resources[24].status=oldStatus;await page.getByLabel('待处理事项关键词',{exact:true}).fill('')
  bounded=true;await refresh();assert.equal(await tab('待发布资源').textContent(),'待发布资源（≥ 5000）')
  await page.getByTestId('dashboard-work-partial').waitFor();await page.screenshot({path:'.runtime/dashboard-work-bounded.png',fullPage:true})
  bounded=false;fixture.resources=resources.map(row=>({...row,_demo:true}));await refresh();await page.getByTestId('dashboard-work-error').waitFor()
  assert.equal(await tab('待发布资源').textContent(),'待发布资源（—）');fixture.resources=resources
  hold=true;const refreshButton=page.getByRole('button',{name:'刷新数据',exact:true});await refreshButton.click()
  await page.waitForFunction(()=>!!document.querySelector('.dashboard .el-loading-mask'))
  const deadline=Date.now()+10000;while(!release){assert.ok(Date.now()<deadline);await page.waitForTimeout(20)}
  await page.evaluate(()=>{history.pushState({},'','/about');dispatchEvent(new PopStateEvent('popstate'))})
  await page.getByRole('heading',{name:'系统说明',exact:true}).waitFor();release();release=null;await page.waitForTimeout(100)
  assert.equal(await page.getByTestId('dashboard-work').count(),0)
  assert.deepEqual(writes,[]);assert.deepEqual(errors,[])
  console.log('Dashboard work passed: real counts; draft/failed metadata summaries; search/paging/sort; three existing route destinations; independent faults/recovery; published item removal; 5000-row limit; foreign-demo rejection; unmount isolation. GET only, no database writes.')
}catch(error){await page?.screenshot({path:'.runtime/dashboard-work-error.png',fullPage:true}).catch(()=>{});throw error}
finally{release?.();await browser?.close();await server.close()}
