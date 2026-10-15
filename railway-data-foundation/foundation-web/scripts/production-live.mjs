import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import net from 'node:net'
import path from 'node:path'
const config=JSON.parse(await readFile(process.argv[2],'utf8'))
for(const url of [config.foundation,config.provider])assert.equal(new URL(url).hostname,'127.0.0.1')
process.env.VITE_USE_MOCK='false'
const port=await new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p))})})
const server=await createServer({cacheDir:path.join(config.evidence,'vite-production-cache'),server:{host:'127.0.0.1',port,strictPort:true,proxy:{'/api':{target:config.foundation,changeOrigin:true},'/provider':{target:config.provider,changeOrigin:true,rewrite:p=>p.replace(/^\/provider/,'')}}}})
let browser,page;const errors=[]
try{
 await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
 page=await browser.newPage({viewport:{width:1600,height:1050},locale:'zh-CN'});page.on('pageerror',e=>errors.push(e.message));page.setDefaultTimeout(30000)
 await page.goto(`http://127.0.0.1:${port}/login`,{waitUntil:'networkidle'})
 await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_PASSWORD)
 await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard')
 await page.getByRole('menuitem',{name:'数据发送',exact:true}).waitFor()
 await page.goto(`http://127.0.0.1:${port}/production?capability=${config.capability}`,{waitUntil:'networkidle'})
 const sourceTable=page.locator('.el-table').first();await sourceTable.getByRole('button',{name:'发送',exact:true}).first().waitFor()
 assert.equal(await sourceTable.locator('.el-table__body-wrapper tbody tr').count(),20)
 await sourceTable.getByRole('button',{name:'查看',exact:true}).first().click();const record=page.locator('.el-dialog:visible');await record.locator('pre').filter({hasText:'container_no'}).waitFor();await record.locator('.el-dialog__headerbtn').click()
 await sourceTable.getByRole('button',{name:'发送',exact:true}).first().click();await page.locator('.el-message-box').getByRole('button',{name:'发送',exact:true}).click();await page.locator('.results .el-tag').getByText('发送成功',{exact:true}).waitFor()
 const records=sourceTable.locator('.el-table__body-wrapper tbody tr');await records.nth(0).locator('.el-checkbox').click();await records.nth(1).locator('.el-checkbox').click()
 await page.getByRole('button',{name:'发送选中记录（2）',exact:true}).click();await page.locator('.el-message-box').getByRole('button',{name:'发送',exact:true}).click()
 await page.waitForFunction(()=>document.querySelectorAll('.results .el-tag--success').length===2)
 await page.screenshot({path:path.join(config.evidence,'provider-real-production.png'),fullPage:true,animations:'disabled'})
 // Read failure must remove source records; retry returns actual source rows.
 await page.route('**/provider/api/v1/production/**/records?**',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'独立发送测试读取故障',data:null})}),{times:1})
 await page.getByRole('button',{name:'查询源记录',exact:true}).click();await page.getByText('独立发送测试读取故障',{exact:true}).waitFor();assert.equal(await sourceTable.getByRole('button',{name:'发送',exact:true}).count(),0)
 await page.getByRole('button',{name:'重试查询',exact:true}).click();await sourceTable.getByRole('button',{name:'发送',exact:true}).first().waitFor()
 // Deliberately injected UI-only 503 never counts as a real publication.
 await page.route('**/provider/api/v1/production/**/send',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:503,message:'测试回执未确认',data:null})}),{times:1})
 await sourceTable.getByRole('button',{name:'发送',exact:true}).first().click();await page.locator('.el-message-box').getByRole('button',{name:'发送',exact:true}).click();await page.locator('.results .el-tag').getByText('结果待确认',{exact:true}).waitFor()
 assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);assert.deepEqual(errors,[])
 await writeFile(path.join(config.evidence,'production-ui-verification.json'),JSON.stringify({real_publications:3,source_rows:20,single_and_batch:true,read_failure_cleared:true,retry_real_query:true,uncertain_send_not_success:true},null,2))
 console.log('真实提供方页面通过：源记录读取、单条及批量共三次发送、故障清空重试、待确认状态、无持久秘密。')
}catch(error){await writeFile(path.join(config.evidence,'production-ui-error.json'),JSON.stringify({url:page?.url(),errors,message:error.message},null,2));await page?.screenshot({path:path.join(config.evidence,'production-ui-error.png'),fullPage:true}).catch(()=>{});throw error}
finally{await browser?.close();await server.close()}
