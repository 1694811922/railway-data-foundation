import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import net from 'node:net'
import path from 'node:path'
const config=JSON.parse(await readFile(process.argv[2],'utf8'))
for(const url of [config.foundation,config.receiver])assert.equal(new URL(url).hostname,'127.0.0.1')
process.env.VITE_USE_MOCK='false'
const port=await new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p))})})
const server=await createServer({cacheDir:path.join(config.evidence,'vite-history-entry-cache'),server:{host:'127.0.0.1',port,strictPort:true,proxy:{'/api':{target:config.foundation,changeOrigin:true},'/demand':{target:config.receiver,changeOrigin:true,rewrite:p=>p.replace(/^\/demand/,'')}}}})
let browser
try{
 await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
 const page=await browser.newPage({viewport:{width:1550,height:1080},locale:'zh-CN'}),errors=[];page.on('pageerror',e=>errors.push(e.message));page.setDefaultTimeout(20000)
 const base=`http://127.0.0.1:${port}`
 await page.goto(base+'/login',{waitUntil:'networkidle'})
 await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_PASSWORD)
 await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard')
 const response=await page.request.get(`${base}/demand/api/v1/receiving-tasks/${config.task_id}`);assert.equal(response.status(),200);const task=(await response.json()).data;assert.equal(task.runtime_state,'BLOCKED');assert.equal(task.received_count,config.expected_messages+3)
 await page.goto(`${base}/kafka-subscriptions?keyword=${encodeURIComponent(task.grant_name)}`,{waitUntil:'networkidle'})
 await page.locator('tbody tr').filter({hasText:task.grant_name}).getByRole('button',{name:'数据接收',exact:true}).click()
 const drawer=page.locator('.el-drawer:visible');await drawer.getByRole('button',{name:'查看内容',exact:true}).first().waitFor();assert.equal(await drawer.getByRole('button',{name:'查看内容',exact:true}).count(),Math.min(20,config.expected_messages+3))
 assert.equal(await page.locator('.el-dialog:visible').count(),0);assert.equal(await page.locator('input[type=password]').count(),0)
 await page.screenshot({path:path.join(config.evidence,'receiver-revoked-history-entry.png'),fullPage:true,animations:'disabled'})
 assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);assert.deepEqual(errors,[])
 console.log(`真实撤权后订阅入口通过：打开本主体已有阻塞任务及${config.expected_messages+3}条历史消息，不重新领取凭据或创建任务。`)
}finally{await browser?.close();await server.close()}
