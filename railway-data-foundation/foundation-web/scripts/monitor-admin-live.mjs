import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import net from 'node:net'
import path from 'node:path'
const c=JSON.parse(await readFile(process.argv[2],'utf8'));assert.equal(new URL(c.foundation).hostname,'127.0.0.1')
process.env.VITE_USE_MOCK='false'
const port=await new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const n=s.address().port;s.close(()=>resolve(n))})})
const server=await createServer({cacheDir:path.join(c.evidence,'vite-monitor-cache'),server:{host:'127.0.0.1',port,strictPort:true,proxy:{'/api':{target:c.foundation,changeOrigin:true}}}})
let browser,page;const errors=[]
try{
 await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
 page=await browser.newPage({viewport:{width:1750,height:1080},locale:'zh-CN'});page.setDefaultTimeout(20000);page.on('pageerror',e=>errors.push(e.message))
 const base=`http://127.0.0.1:${port}`
 await page.goto(base+'/login',{waitUntil:'networkidle'});await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_PASSWORD);await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard')
 await page.goto(base+'/kafka-monitor',{waitUntil:'networkidle'});await page.getByRole('heading',{name:'传输监测',exact:true}).waitFor();assert.ok(page.url().includes('/transfer-monitor'))
 for(const name of ['调用审计','调用追踪','共享计量','Kafka 传输监测'])assert.equal(await page.getByRole('menuitem',{name,exact:true}).count(),0)
 await page.getByRole('button',{name:'更多筛选',exact:true}).click();await page.getByRole('textbox',{name:'接收任务编号',exact:true}).fill(c.task_id);await page.getByRole('button',{name:'查询',exact:true}).click()
 const table=page.getByTestId('transfer-monitor-table');await table.getByText('任务状态',{exact:true}).first().waitFor()
 const response=await page.request.get(base+'/api/v1/transfer-monitor/events?'+new URLSearchParams({from:new Date(Date.now()-3600000).toISOString(),until:new Date(Date.now()+1000).toISOString(),taskId:c.task_id,limit:50,offset:0}));assert.equal(response.status(),200)
 const actual=(await response.json()).data;assert.ok(actual.items.some(r=>r.reason_code==='AUTHORIZATION_INVALID'||r.reason_code==='KAFKA_AUTH_FAILED'))
 const stored=actual.items.filter(r=>r.stage==='KAFKA_STORE');assert.ok(stored.length>0);assert.ok(stored.some(r=>Number.isSafeInteger(r.duration_ms)&&r.duration_ms>=0&&r.duration_scope==='DB_TRANSACTION'),'Real committed-batch storage duration must arrive')
 assert.ok(actual.summary.received_records>=4);assert.ok(actual.summary.kafka_sent>=4)
 await table.getByRole('button',{name:'详情',exact:true}).first().click();const details=page.getByRole('dialog',{name:'传输记录详情',exact:true});await details.getByText('接收端自报',{exact:true}).waitFor();await details.getByText('底座收到时间',{exact:true}).waitFor()
 await page.screenshot({path:path.join(c.evidence,'admin-receiver-monitor.png'),fullPage:true,animations:'disabled'});await details.locator('.el-drawer__close-btn').click()
 await page.route('**/api/v1/transfer-monitor/events?**',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'独立管理员监测故障',data:null})}),{times:1})
 await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByTestId('transfer-monitor-error').waitFor();assert.equal(await table.count(),0);assert.equal((await page.getByTestId('transfer-stat-received_records').textContent()).trim(),'—')
 await page.getByRole('button',{name:'重试',exact:true}).click();await table.getByText('任务状态',{exact:true}).first().waitFor()
 await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:'处理阶段',exact:true})}).click();await page.getByRole('option',{name:'接收落库',exact:true}).click();await page.getByRole('button',{name:'查询',exact:true}).click();await table.getByText('接收落库',{exact:true}).first().waitFor()
 for(const tr of await table.locator('tbody tr').all())assert.ok((await tr.textContent()).includes('接收落库'))
 await page.screenshot({path:path.join(c.evidence,'unified-transfer-monitor.png'),fullPage:true,animations:'disabled'})
 // Hold one real reply, change the filter, then release the old reply: old rows must not win.
 let release,started;const entered=new Promise(r=>started=r),held=new Promise(r=>release=r)
 await page.route('**/api/v1/transfer-monitor/events?**',async r=>{const reply=await r.fetch();started();await held;await r.fulfill({response:reply})},{times:1})
 await page.getByRole('button',{name:'刷新',exact:true}).click();await entered
 await page.getByRole('textbox',{name:'接收任务编号',exact:true}).fill('RCV_'+'f'.repeat(32));await page.getByRole('button',{name:'查询',exact:true}).click();await page.getByText('所选范围暂无已上报传输记录',{exact:true}).waitFor();release();await page.waitForLoadState('networkidle');assert.equal(await table.getByText('接收落库',{exact:true}).count(),0)
 for(const old of ['/runtime-events','/runtime-traces','/metrics']){await page.goto(base+old,{waitUntil:'networkidle'});await page.getByRole('heading',{name:'传输监测',exact:true}).waitFor();assert.ok(page.url().includes('/transfer-monitor'))}
 assert.deepEqual(errors,[]);assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0)
 console.log('Unified administrator monitor passed: actual send/store summaries and post-commit batch duration, revoked event, mixed timeline, stage filtering, unknown/failure retry, late reply isolation, legacy routes and no secrets.')

}catch(e){await page?.screenshot({path:path.join(c.evidence,'monitor-browser-error.png'),fullPage:true}).catch(()=>{});await writeFile(path.join(c.evidence,'monitor-browser-error.json'),JSON.stringify({message:e.message,errors}));throw e}
finally{await browser?.close();await server.close()}
