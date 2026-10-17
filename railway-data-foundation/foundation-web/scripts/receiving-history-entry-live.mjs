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
 await page.goto(base+'/receiving',{waitUntil:'networkidle'})
 const taskRow=page.locator('tbody tr').filter({hasText:task.task_name})
 const progressReply=page.waitForResponse(r=>r.url().endsWith(`/receiving-tasks/${config.task_id}/progress`))
 await taskRow.getByRole('button',{name:'接收进度',exact:true}).click();assert.equal((await progressReply).status(),403)
 const progress=page.getByRole('dialog',{name:'接收进度',exact:true});await progress.locator('.el-alert--error').waitFor()
 assert.equal(new URL(page.url()).pathname,'/receiving');assert.equal((await page.request.get(base+'/api/v1/auth/me')).status(),200)
 await progress.getByRole('button',{name:'重试',exact:true}).click();await progress.locator('.el-alert--error').waitFor();assert.equal(new URL(page.url()).pathname,'/receiving')
 await page.screenshot({path:path.join(config.evidence,'receiver-progress-revoked-stays-logged-in.png'),fullPage:true,animations:'disabled'})
 await progress.getByRole('button',{name:'关闭',exact:true}).click()
 // Genuine expired sessions must still clear identity and return to login.
 const csrf=(await (await page.request.get(base+'/api/v1/auth/csrf')).json()).data
 assert.equal((await page.request.post(base+'/api/v1/auth/logout',{headers:{[csrf.header_name]:csrf.token}})).status(),200)
 await taskRow.getByRole('button',{name:'接收进度',exact:true}).click();await page.waitForURL('**/login**')
 assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);assert.deepEqual(errors,[])
 console.log(`真实撤权后历史入口与进度通过：保留${config.expected_messages+3}条历史消息，订阅失效403保留网页登录，真实登录失效401跳转登录。`)
}finally{await browser?.close();await server.close()}
