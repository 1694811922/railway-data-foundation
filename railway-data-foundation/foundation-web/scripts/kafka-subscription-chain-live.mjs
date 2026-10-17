import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import net from 'node:net'
import path from 'node:path'

// Real isolated Foundation and SCRAM Broker. No intercepted success and no saved password.
const config=JSON.parse(await readFile(process.argv[2],'utf8'))
assert.equal(new URL(config.foundation).hostname,'127.0.0.1')
process.env.VITE_USE_MOCK='false'
const freePort=()=>new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p))})})
const server=await createServer({server:{host:'127.0.0.1',port:await freePort(),strictPort:true,proxy:{'/api':{target:config.foundation,changeOrigin:true}}}})
let browser,page,secretVisible=false
async function data(response){assert.ok(response.ok(),'HTTP '+response.status());const body=await response.json();assert.equal(body.code,0);return body.data}
try{
 await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
 page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'});page.setDefaultTimeout(15000)
 const errors=[];page.on('pageerror',e=>errors.push(e.message))
 const base='http://127.0.0.1:'+server.httpServer.address().port
 await page.goto(base+'/',{waitUntil:'networkidle'});await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_PASSWORD);await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard');const csrf=(await (await page.request.get(base+'/api/v1/auth/csrf')).json()).data;await page.context().setExtraHTTPHeaders({[csrf.header_name]:csrf.token})
 await page.goto(base+'/kafka-subscriptions?keyword='+config.grant_id,{waitUntil:'networkidle'})
 await page.getByRole('heading',{name:'共享授权',exact:true}).waitFor()
 const row=page.getByTestId('subscription-table').getByRole('row').filter({hasText:config.grant_name})
 await row.getByText('未配置权限',{exact:true}).waitFor()
 async function action(label){
  await row.getByRole('button',{name:'订阅操作',exact:true}).click();await page.getByRole('menuitem',{name:label,exact:true}).click()
  const dialog=page.getByRole('dialog').filter({hasText:label})
  await dialog.getByRole('textbox',{name:'审批操作原因',exact:true}).fill('真实浏览器验证：'+label)
  await dialog.getByRole('button',{name:'确认操作',exact:true}).click()
 }
 await action('配置订阅权限');await row.getByText('已配置',{exact:true}).waitFor()
 await action('领取订阅凭据');secretVisible=true
 const secret=page.getByTestId('subscription-secret');await secret.waitFor()
 // Keep the secret only in this local memory, never a screenshot, log or evidence file.
 const password=await secret.textContent();assert.ok(/^ks_[A-Za-z0-9_-]{43}$/.test(password),'Unexpected credential format')
 assert.equal(await page.evaluate(p=>[localStorage,sessionStorage].some(s=>Object.values(s).some(v=>v.includes(p))),password),false)
 const reportToken=await page.getByTestId('consumption-secret').textContent();assert.ok(/^rkc_[A-Za-z0-9_-]{43}$/.test(reportToken));assert.equal(await page.evaluate(p=>[localStorage,sessionStorage].some(s=>Object.values(s).some(v=>v.includes(p))),reportToken),false)
 await page.getByRole('button',{name:'我已记录，关闭',exact:true}).click();await secret.waitFor({state:'detached'});secretVisible=false
 const api=config.foundation+'/api/v1/kafka-subscriptions/'+config.grant_id
 const claimed=await data(await page.request.get(api));assert.equal(claimed.credential_available,false)
 assert.equal(JSON.stringify(claimed).includes(password)||JSON.stringify(claimed).includes(reportToken),false)
 const again=await page.request.post(api+'/credential',{data:{version:claimed.version,reason:'重复领取应拒绝'}});assert.equal(again.status(),400)
 await action('停止订阅');await row.getByText('已撤权',{exact:true}).waitFor()
 await row.getByRole('button',{name:'查看',exact:true}).click()
 await page.getByText('已核对撤权',{exact:true}).waitFor()
 await page.screenshot({path:path.join(config.evidence,'subscription-real-revoked.png'),fullPage:true,animations:'disabled'})
 assert.deepEqual(errors,[])
 await writeFile(path.join(config.evidence,'subscription-browser-result.json'),JSON.stringify({passed:true,mocks:false,one_time:true,storage_empty_of_secret:true,provisioned:true,revoked:true},null,2))
 console.log('Real subscription browser flow passed: provision, one-time credentials, stop, actual permission history.')
}catch(error){if(!secretVisible)await page?.screenshot({path:path.join(config.evidence,'subscription-browser-error.png'),fullPage:true}).catch(()=>{});throw error}
finally{await browser?.close();await server.close()}
