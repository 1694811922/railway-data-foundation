import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import path from 'node:path'

// Entire lifecycle hits the real isolated services. No route interception, mock state or payload storage.
const config=JSON.parse(await readFile(process.argv[2],'utf8'))
for(const base of [config.foundation,config.provider]){
 const url=new URL(base);assert.equal(url.hostname,'127.0.0.1');assert.equal(url.protocol,'http:');assert.equal(url.pathname,'/')
}
const token=process.env.KAFKA_LIVE_CONTROL_TOKEN;delete process.env.KAFKA_LIVE_CONTROL_TOKEN
assert.ok(token)
process.env.VITE_USE_MOCK='false'
const server=await createServer({server:{host:'127.0.0.1',port:0,proxy:{'/api':{target:config.foundation,changeOrigin:true},'/provider':{target:config.provider,changeOrigin:true,rewrite:p=>p.replace(/^\/provider/,'')}}}})
const api=config.foundation+'/api/v1',id=config.capability_id
let browser,page
async function data(response){assert.ok(response.ok(),'HTTP '+response.status());const body=await response.json();assert.equal(body.code,0);return body.data}
const publish=()=>page.request.post(config.provider+'/containers/events/1/publish',{headers:{'X-Provider-Token':token,'X-Capability-Id':id}})
try{
 await server.listen()
 browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
 page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'});page.setDefaultTimeout(15000)
 const errors=[];page.on('pageerror',e=>errors.push(e.message))
 const base='http://127.0.0.1:'+server.httpServer.address().port
 await page.goto(base+'/',{waitUntil:'networkidle'});await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_PASSWORD);await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard');const csrf=(await (await page.request.get(base+'/api/v1/auth/csrf')).json()).data;await page.context().setExtraHTTPHeaders({[csrf.header_name]:csrf.token})
 await page.goto(base+'/capabilities',{waitUntil:'networkidle'})
 await page.getByRole('heading',{name:'接口能力管理',exact:true}).waitFor()
 await page.getByPlaceholder('搜索能力名称、编号、资源或访问地址').fill(config.capability_name)
 const row=page.getByTestId('capability-table').getByRole('row').filter({hasText:config.capability_name})
 await row.waitFor()
 assert.equal((await publish()).status(),403)
 assert.equal(await row.getByRole('button',{name:'启用',exact:true}).isDisabled(),true)
 await row.getByRole('button',{name:'验证',exact:true}).click();await row.getByText('已验证',{exact:true}).waitFor()
 await row.getByRole('button',{name:'启用',exact:true}).click();await row.getByText('已启用',{exact:true}).waitFor()
 await row.getByRole('button',{name:'查看',exact:true}).click()
 const dialog=page.getByRole('dialog');await dialog.getByText('允许提供服务发送新消息',{exact:true}).waitFor()
 await dialog.getByText(/读取权限在/).waitFor()
 await page.screenshot({path:path.join(config.evidence,'kafka-enabled.png'),fullPage:true,animations:'disabled'})
 await dialog.locator('.el-dialog__headerbtn').click()
 const response=await publish(),receipt=await data(response),trace=response.headers()['x-trace-id']
 assert.ok(trace);assert.ok(Number.isSafeInteger(receipt.offset));assert.equal(receipt.partition,0)
 await row.getByRole('button',{name:'停用',exact:true}).click();await row.getByText('已停用',{exact:true}).waitFor()
 const stopped=await publish();assert.equal(stopped.status(),403);assert.equal((await stopped.json()).data,null)
 await row.getByRole('button',{name:'验证',exact:true}).click();await row.getByText('已停用',{exact:true}).waitFor()
 assert.equal((await data(await page.request.get(api+'/capabilities/'+id))).status,'DISABLED')
 await page.screenshot({path:path.join(config.evidence,'kafka-disabled.png'),fullPage:true,animations:'disabled'})
 const noId=await page.request.post(config.provider+'/containers/events/1/publish',{headers:{'X-Provider-Token':token}});assert.equal(noId.status(),400)
 const badIdentity=await page.request.post(config.provider+'/containers/events/1/publish',{headers:{'X-Provider-Token':'invalid','X-Capability-Id':id}});assert.equal(badIdentity.status(),403)
 // Re-enable with real Broker probing, then stop the Source; subsequent production must be rejected.
 await row.getByRole('button',{name:'启用',exact:true}).click();await row.getByText('已启用',{exact:true}).waitFor()
 const capability=await data(await page.request.get(api+'/capabilities/'+id)),resource=await data(await page.request.get(api+'/resources/'+capability.resource_id))
 try{
  await data(await page.request.patch(api+'/sources/'+resource.source_id+'/status',{data:{status:'DISABLED'}}))
  assert.equal((await publish()).status(),403)
 }finally{await data(await page.request.patch(api+'/sources/'+resource.source_id+'/status',{data:{status:'ENABLED'}}))}
 await row.getByRole('button',{name:'停用',exact:true}).click();await row.getByText('已停用',{exact:true}).waitFor()
 // Wait for the actual asynchronous delivery and show the real audit record in the browser.
 let event
 const deadline=Date.now()+15000
 while(Date.now()<deadline){
  const result=await data(await page.request.get(api+'/runtime-traces/'+trace))
  if(result.length===1){event=result[0];break}
  await new Promise(resolve=>setTimeout(resolve,100))
 }
 assert.ok(event,'Actual Provider summary did not arrive');assert.equal(event.record_count,1);assert.equal(event.topic_offset,receipt.offset)
 await page.goto(base+'/runtime-events',{waitUntil:'networkidle'});await page.getByRole('heading',{name:'调用审计',exact:true}).waitFor()
 await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:'采集位置',exact:true})}).click();await page.getByRole('option',{name:'消息生产',exact:true}).click()
 await page.getByRole('textbox',{name:'追踪编号筛选',exact:true}).fill(trace);await page.getByRole('button',{name:'查询',exact:true}).click()
 const audit=page.getByTestId('audit-table');await audit.getByText('执行成功',{exact:true}).waitFor()
 await page.screenshot({path:path.join(config.evidence,'kafka-real-audit.png'),fullPage:true,animations:'disabled'})
 assert.deepEqual(errors,[])
 await writeFile(path.join(config.evidence,'browser-result.json'),JSON.stringify({passed:true,published:receipt,trace_id:trace,disabled_status:403,missing_id_status:400,wrong_operator_status:403,source_disabled_status:403,mocks:false},null,2))
 console.log('Real Kafka browser complete chain passed: SCRAM metadata/enable/send/disable, Source stop, actual audit. No intercepted success.')
}catch(error){await page?.screenshot({path:path.join(config.evidence,'browser-error.png'),fullPage:true}).catch(()=>{});throw new Error(String(error.message).split('Call log:')[0].replaceAll(token,'[REDACTED]'))}
finally{await browser?.close();await server.close()}
