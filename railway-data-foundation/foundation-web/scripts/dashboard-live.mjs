import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { mkdir } from 'node:fs/promises'
import { loadDashboard, dashboardMetrics } from '../src/utils/dashboard.js'

process.env.VITE_USE_MOCK='false'
const base = process.env.FOUNDATION_BASE_URL || 'http://127.0.0.1:18080/api/v1'
const endpoints={sources:'sources',tasks:'scan-tasks',resources:'resources',capabilities:'capabilities'}
const fetchers=Object.fromEntries(Object.entries(endpoints).map(([key,path])=>[key,async params=>{
  const response=await fetch(base+'/'+path+'?'+new URLSearchParams(params))
  const envelope=await response.json()
  assert.equal(response.ok,true,'Real API unavailable: '+path)
  assert.equal(envelope.code,0)
  return envelope.data
}]))
const realData=await loadDashboard(fetchers)
assert.equal(Object.values(realData).some(dataset=>dataset.error),false,'Start Foundation before running this test')
const server=await createServer({server:{host:'127.0.0.1',port:5178,strictPort:true}})
let browser
try {
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const page=await browser.newPage({viewport:{width:1600,height:1050},locale:'zh-CN'})
  const errors=[], writes=[]
  page.on('pageerror',error=>errors.push(error.message))
  page.on('request',request=>{ if(request.url().includes('/api/v1/') && request.method()!=='GET') writes.push(request.method()) })
  const number=key=>page.getByTestId('stat-'+key).locator('.stat-number')
  async function waitRead() {
    await page.getByRole('heading',{name:'首页概览',exact:true}).waitFor()
    await page.waitForFunction(()=>!document.querySelector('.dashboard .el-loading-mask') && !document.querySelector('.dashboard-stat .stat-note')?.textContent.includes('正在读取'))
  }
  await page.goto('http://127.0.0.1:5178/',{waitUntil:'networkidle'})
  await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await waitRead()
  for(const metric of dashboardMetrics(realData)) assert.equal((await number(metric.key).textContent()).trim(),(metric.partial?'≥ ':'')+metric.value)
  await page.getByText('真实接口统计',{exact:true}).waitFor()
  assert.equal(await page.getByTestId('dashboard-errors').count(),0)
  assert.equal(await page.getByText('已显式开启前端演示模式。',{exact:false}).count(),0)
  await mkdir('.runtime',{recursive:true})
  await page.screenshot({path:'.runtime/dashboard-real.png',fullPage:true})

  // HTTP response fixtures exercise pagination/error recovery without inserting DB records.
  const today=new Date().toISOString(), yesterday=new Date(Date.now()-86400000).toISOString()
  const fixture={
    sources:[{source_id:'SRC_TEST_ON',status:'ENABLED'},{source_id:'SRC_TEST_OFF',status:'DISABLED'}],
    resources:Array.from({length:451},(_,i)=>({resource_id:'RES_TEST_'+i,resource_name:'分页验收资源 '+i,source_id:i%2?'SRC_TEST_OFF':'SRC_TEST_ON',status:i%3?'PUBLISHED':'DRAFT',created_at:i===450?today:yesterday,updated_at:today})),
    capabilities:Array.from({length:451},(_,i)=>({capability_id:'CAP_TEST_'+i,resource_id:'RES_TEST_'+i,type:['REST_API','KAFKA_TOPIC','CDC_STREAM','READONLY_QUERY'][i%4],status:i%5?'ENABLED':'DRAFT',health_status:i%7?'UP':'DOWN'})),
    tasks:Array.from({length:205},(_,i)=>({task_id:'SCAN_TEST_'+i,started_at:i<101?today:yesterday})),
  }
  let failing=false, bounded=false
  const seen=[]
  await page.route('**/api/v1/**',async route=>{
    const url=new URL(route.request().url())
    const key=Object.keys(endpoints).find(key=>url.pathname==='/api/v1/'+endpoints[key])
    if(!key) throw new Error('Unexpected API in Dashboard: '+url.pathname)
    const offset=Number(url.searchParams.get('offset')), limit=Number(url.searchParams.get('limit'))
    seen.push({key,offset,limit})
    assert.ok(limit>=1 && limit<=200)
    if(failing && key==='resources' && offset===200) return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10001,message:'验收用接口故障',data:null})})
    const rows=bounded && key==='sources'?Array.from({length:5001},(_,i)=>({source_id:'SRC_BOUND_'+i,status:'ENABLED'})):fixture[key]
    return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,message:'success',data:rows.slice(offset,offset+limit)})})
  })
  await page.getByRole('button',{name:'刷新数据',exact:true}).click()
  await waitRead()
  const dataset=Object.fromEntries(Object.entries(fixture).map(([key,rows])=>[key,{rows,complete:true,error:false}]))
  for(const metric of dashboardMetrics(dataset)) assert.equal((await number(metric.key).textContent()).trim(),String(metric.value))
  assert.ok(seen.some(item=>item.key==='resources' && item.offset===400))
  assert.ok(seen.some(item=>item.key==='capabilities' && item.offset===400))
  assert.equal((await page.getByTestId('dashboard-resources').getByRole('row').nth(1).textContent()).includes('分页验收资源 450'),true)
  await page.screenshot({path:'.runtime/dashboard-pagination.png',fullPage:true})

  failing=true
  await page.getByRole('button',{name:'刷新数据',exact:true}).click()
  await waitRead()
  assert.equal((await number('resources').textContent()).trim(),'—')
  assert.equal((await number('available').textContent()).trim(),'—')
  assert.equal((await number('capabilities').textContent()).trim(),'451')
  await page.getByTestId('dashboard-errors').waitFor()
  assert.equal(await page.getByText('广州港集装箱作业事件数据集',{exact:true}).count(),0,'Failure introduced a demo resource')
  await page.screenshot({path:'.runtime/dashboard-failure.png',fullPage:true})
  failing=false
  await page.getByRole('button',{name:'刷新数据',exact:true}).click()
  await waitRead()
  assert.equal((await number('resources').textContent()).trim(),'300')
  assert.equal(await page.getByTestId('dashboard-errors').count(),0)

  bounded=true
  await page.getByRole('button',{name:'刷新数据',exact:true}).click()
  await waitRead()
  assert.equal((await number('sources').textContent()).trim(),'≥ 5000')
  await page.getByTestId('dashboard-incomplete').waitFor()
  assert.ok(seen.some(item=>item.key==='sources' && item.offset===5000 && item.limit===1))
  await page.screenshot({path:'.runtime/dashboard-bounded.png',fullPage:true})
  assert.deepEqual(writes,[],'Dashboard made a mutating API call')
  assert.deepEqual(errors,[])
  console.log('Dashboard passed: real API counts, 451-row pagination, recent resources, failed second page, recovery, 5000-row bound. No database writes.')
} finally {
  await browser?.close()
  await server.close()
}
