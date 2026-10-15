import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import net from 'node:net'
import path from 'node:path'
const config=JSON.parse(await readFile(process.argv[2],'utf8'))
for(const url of [config.foundation,config.receiver])assert.equal(new URL(url).hostname,'127.0.0.1')
process.env.VITE_USE_MOCK='false'
const port=await new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p))})})
const server=await createServer({cacheDir:path.join(config.evidence,'vite-receiving-cache'),server:{host:'127.0.0.1',port,strictPort:true,proxy:{'/api':{target:config.foundation,changeOrigin:true},'/demand':{target:config.receiver,changeOrigin:true,rewrite:p=>p.replace(/^\/demand/,'')}}}})
let browser,page,downloadCount=0
const errors=[]
try{
  await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1550,height:1080},locale:'zh-CN'});page.on('pageerror',e=>errors.push(e.message));page.setDefaultTimeout(30000)
  page.on('download',()=>downloadCount++)
  await page.goto(`http://127.0.0.1:${port}/login`,{waitUntil:'networkidle'})
  await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_PASSWORD)
  await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard')
  const taskResponse=await page.request.get(`http://127.0.0.1:${port}/demand/api/v1/receiving-tasks/${config.task_id}`);assert.equal(taskResponse.status(),200);const task=(await taskResponse.json()).data;assert.equal(task.received_count,config.expected_messages+3)
  const grant=task.grant_id,entry=`http://127.0.0.1:${port}/receiving?grant=${grant}`
  await page.goto(`http://127.0.0.1:${port}/kafka-subscriptions?keyword=${encodeURIComponent(task.grant_name)}`,{waitUntil:'networkidle'})
  await page.locator('tbody tr').filter({hasText:task.grant_name}).getByRole('button',{name:'数据接收',exact:true}).click()
  await page.locator('.el-drawer:visible').getByRole('button',{name:'查看内容',exact:true}).first().waitFor();assert.equal(new URL(page.url()).searchParams.get('grant'),grant);assert.equal(await page.locator('input[type=password]').count(),0)
  assert.equal(await page.locator('.el-drawer:visible').getByRole('button',{name:'查看内容',exact:true}).count(),Math.min(20,config.expected_messages+3))
  await page.locator('.el-drawer:visible .el-drawer__close-btn').click()
  await page.route('**/demand/api/v1/receiving-tasks?**',async r=>{if(new URL(r.request().url()).searchParams.get('grantId')===grant)await r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'独立关联测试故障',data:null})});else await r.continue()},{times:1})
  await page.goto(entry,{waitUntil:'networkidle'});await page.getByText('独立关联测试故障',{exact:true}).waitFor();assert.equal(await page.locator('.el-dialog:visible').count(),0);assert.equal(await page.locator('.el-drawer:visible').count(),0)
  await page.getByRole('button',{name:'重试',exact:true}).click();await page.locator('.el-drawer:visible').getByRole('button',{name:'查看内容',exact:true}).first().waitFor()
  await page.locator('.el-drawer:visible .el-drawer__close-btn').click()
  let releaseEntry,entryFetched,entryFinished
  const entryHold=new Promise(resolve=>{releaseEntry=resolve}),fetchedEntry=new Promise(resolve=>{entryFetched=resolve}),finishedEntry=new Promise(resolve=>{entryFinished=resolve})
  await page.route('**/demand/api/v1/receiving-tasks?**',async r=>{try{const response=await r.fetch();entryFetched();await entryHold;await r.fulfill({response})}catch{}finally{entryFinished()}},{times:1})
  await page.goto(entry,{waitUntil:'domcontentloaded'});await fetchedEntry;await page.getByRole('button',{name:'查看全部任务',exact:true}).click();await page.getByRole('button',{name:'查看数据',exact:true}).first().waitFor();releaseEntry();await finishedEntry
  assert.equal(await page.locator('.el-drawer:visible').count(),0);assert.equal(await page.locator('.el-dialog:visible').count(),0)
  await page.getByRole('button',{name:'创建接收任务',exact:true}).click();const creation=page.locator('.el-dialog:visible');await creation.locator('.el-select').click()
  await page.getByRole('option',{name:task.grant_name+' · '+task.topic,exact:true}).click();await page.locator('.el-drawer:visible').getByRole('button',{name:'查看内容',exact:true}).first().waitFor();await page.locator('.el-dialog:visible').waitFor({state:'hidden'})
  assert.equal(await page.locator('.el-drawer:visible').getByRole('button',{name:'查看内容',exact:true}).count(),Math.min(20,config.expected_messages+3))
  await page.locator('.el-drawer:visible .el-drawer__close-btn').click()
  await page.goto(`http://127.0.0.1:${port}/receiving`,{waitUntil:'networkidle'})
  await page.getByRole('button',{name:'查看数据',exact:true}).click();const drawer=page.locator('.el-drawer:visible');await drawer.getByRole('button',{name:'查看内容',exact:true}).first().waitFor()
  await drawer.getByRole('button',{name:'查看内容',exact:true}).first().click();const dialog=page.locator('.el-dialog:visible');await dialog.getByText('消息内容',{exact:true}).first().waitFor();await dialog.locator('pre').filter({hasText:'container_no'}).waitFor()
  assert.match(await dialog.locator('pre').nth(1).innerText(),/container_no/);assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0)
  const displayed=JSON.parse(await dialog.locator('pre').nth(1).innerText())
  const [contentDownload]=await Promise.all([page.waitForEvent('download'),dialog.getByRole('button',{name:'下载内容',exact:true}).click()])
  const contentFile=path.join(config.evidence,'received-content.json');await contentDownload.saveAs(contentFile);assert.deepEqual(JSON.parse(await readFile(contentFile,'utf8')),displayed)
  const [recordDownload]=await Promise.all([page.waitForEvent('download'),dialog.getByRole('button',{name:'下载完整消息',exact:true}).click()])
  const recordFile=path.join(config.evidence,'received-record.json');await recordDownload.saveAs(recordFile);const full=JSON.parse(await readFile(recordFile,'utf8'));assert.equal(full.task_id,config.task_id);assert.deepEqual(JSON.parse(full.message_value.content),displayed);assert.ok(Array.isArray(full.headers))
  await page.screenshot({path:path.join(config.evidence,'receiver-real-data.png'),fullPage:true,animations:'disabled'})
  await dialog.locator('.el-dialog__headerbtn').click()
  const exportEvent=page.waitForEvent('download');await drawer.getByRole('button',{name:'导出筛选结果',exact:true}).click();await page.locator('.el-message-box').getByRole('button',{name:'导出',exact:true}).click()
  const exportDownload=await exportEvent;const exportFile=path.join(config.evidence,'received-export.jsonl');await exportDownload.saveAs(exportFile);const lines=(await readFile(exportFile,'utf8')).trim().split('\n').map(line=>JSON.parse(line));assert.equal(lines.length,config.expected_messages+3);assert.ok(lines.every(line=>line.task_id===config.task_id&&!('encrypted_secrets' in line)))
  await page.route('**/demand/api/v1/receiving-tasks/*/messages/export**',r=>r.fulfill({status:413,contentType:'application/json',body:JSON.stringify({code:413,message:'独立下载测试超限',data:null})}),{times:1})
  await drawer.getByRole('button',{name:'导出筛选结果',exact:true}).click();await page.locator('.el-message-box').getByRole('button',{name:'导出',exact:true}).click();await drawer.getByText('独立下载测试超限',{exact:true}).waitFor()
  let releaseExport,exportFetched,exportFinished
  const exportHold=new Promise(resolve=>{releaseExport=resolve}),fetchedExport=new Promise(resolve=>{exportFetched=resolve}),finishedExport=new Promise(resolve=>{exportFinished=resolve})
  await page.route('**/demand/api/v1/receiving-tasks/*/messages/export**',async r=>{try{const response=await r.fetch();exportFetched();await exportHold;await r.fulfill({response})}catch{}finally{exportFinished()}},{times:1})
  await drawer.getByRole('button',{name:'导出筛选结果',exact:true}).click();await page.locator('.el-message-box').getByRole('button',{name:'导出',exact:true}).click();await fetchedExport;await drawer.getByRole('button',{name:'取消下载',exact:true}).click();releaseExport();await finishedExport;assert.equal(downloadCount,3)
  await page.route('**/demand/api/v1/receiving-tasks/*/messages?**',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'独立接收测试故障',data:null})}),{times:1})
  await drawer.getByRole('button',{name:'刷新',exact:true}).click();await drawer.getByText('独立接收测试故障',{exact:true}).waitFor();assert.equal(await drawer.getByRole('button',{name:'查看内容',exact:true}).count(),0)
  await drawer.getByRole('button',{name:'重试',exact:true}).click();await drawer.getByRole('button',{name:'查看内容',exact:true}).first().waitFor()
  await drawer.locator('.el-drawer__close-btn').click()
  await page.getByRole('button',{name:'接收进度',exact:true}).first().click()
  const progress=page.locator('.el-dialog:visible');await progress.getByText('已追平查询时位置',{exact:true}).first().waitFor()
  await progress.getByText('Kafka已提交位置',{exact:true}).waitFor()
  await page.screenshot({path:path.join(config.evidence,'receiver-real-progress.png'),fullPage:true,animations:'disabled'})
  await page.route('**/demand/api/v1/receiving-tasks/*/progress',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'独立进度测试故障',data:null})}),{times:1})
  await progress.getByRole('button',{name:'刷新进度',exact:true}).click();await progress.getByText('独立进度测试故障',{exact:true}).waitFor();assert.equal(await progress.locator('.el-table').count(),0)
  await progress.getByRole('button',{name:'重试',exact:true}).click();await progress.getByText('已追平查询时位置',{exact:true}).first().waitFor()
  let releaseLate,signalFetched
  const hold=new Promise(resolve=>{releaseLate=resolve}),fetched=new Promise(resolve=>{signalFetched=resolve})
  await page.route('**/demand/api/v1/receiving-tasks/*/progress',async r=>{const response=await r.fetch();signalFetched();await hold;await r.fulfill({response})},{times:1})
  const lateResponse=page.waitForResponse(r=>r.url().endsWith('/progress')&&r.status()===200)
  await progress.getByRole('button',{name:'刷新进度',exact:true}).click();await fetched;await progress.getByRole('button',{name:'关闭',exact:true}).click();releaseLate();await lateResponse
  assert.equal(await page.getByText('落库下一位置',{exact:true}).count(),0)
  await page.getByRole('button',{name:'停止',exact:true}).first().click();await page.getByText('已停止',{exact:true}).first().waitFor()
  await page.getByRole('button',{name:'启动',exact:true}).first().click();await page.getByText('接收中',{exact:true}).first().waitFor()
  assert.deepEqual(errors,[])
  console.log('真实需求方页面通过：订阅打开已有任务、关联失败重试及晚响应隔离；原始内容/完整消息下载、JSONL导出、错误Blob提示、真实进度、停止/启动，无浏览器持久秘密。')
}catch(error){
  await writeFile(path.join(config.evidence,'receiver-ui-error.json'),JSON.stringify({url:page?.url(),errors},null,2))
  await page?.screenshot({path:path.join(config.evidence,'receiver-ui-error.png'),fullPage:true}).catch(()=>{})
  throw error
}finally{await browser?.close();await server.close()}
